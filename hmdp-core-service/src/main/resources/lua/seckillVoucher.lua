-- ============================================
-- 秒杀优惠券核心 Lua 脚本
-- 作用：原子性地完成库存扣减、一人一单检查、日志记录
-- ============================================

-- ========== 参数定义 ==========
-- KEYS 数组（从 Java 传入的 3 个 Redis Key）
local stockKey = KEYS[1]          -- 库存 Key: seckill:stock:{voucherId}
local seckillUserKey = KEYS[2]    -- 已购用户 Set Key: seckill:user:{voucherId}
local traceLogKey = KEYS[3]       -- 追踪日志 Hash Key: seckill:trace:log:{voucherId}

-- ARGV 数组（从 Java 传入的 9 个参数）
local voucherId = ARGV[1]         -- 优惠券 ID
local userId = ARGV[2]            -- 用户 ID
local beginTime = tonumber(ARGV[3])   -- 秒杀开始时间戳（毫秒）
local endTime = tonumber(ARGV[4])     -- 秒杀结束时间戳（毫秒）
local status = tonumber(ARGV[5])      -- 优惠券状态：1=上架，2=下架，3=删除
local orderId = ARGV[6]           -- 订单 ID
local traceId = ARGV[7]           -- 追踪 ID（用于链路追踪和对账）
local logType = ARGV[8]           -- 日志类型：DEDUCT=扣减，RESTORE=恢复
local ttlSeconds = tonumber(ARGV[9])  -- Redis Key 的过期时间（秒）

-- ========== 获取当前时间 ==========
-- redis.call('TIME') 返回 [秒，微秒]，转换为毫秒
local timeArr = redis.call('TIME')
local nowMillis = tonumber(timeArr[1]) * 1000 + math.floor(tonumber(timeArr[2]) / 1000)

-- ========== 第一关：检查活动时间 ==========
-- 如果当前时间 < 开始时间，说明活动未开始
-- 考虑 100ms 的宽容度，避免因时钟精度和网络延迟导致的问题
local toleranceMillis = 100  -- 100 毫秒宽容度
if nowMillis < (beginTime - toleranceMillis) then
    return string.format('{"%s": %d}', 'code', 10002)  -- 错误码 10002：活动未开始
end


-- 如果当前时间 > 结束时间，说明活动已结束
if nowMillis > endTime then
    return string.format('{"%s": %d}', 'code', 10003)  -- 错误码 10003：活动已结束
end

-- ========== 第二关：检查优惠券状态 ==========
-- status = 2 表示已下架
if (status == 2) then
    return string.format('{"%s": %d}', 'code', 10011)  -- 错误码 10011：优惠券已下架
end

-- status = 3 表示已删除
if (status == 3) then
    return string.format('{"%s": %d}', 'code', 10012)  -- 错误码 10012：优惠券已删除
end

-- ========== 第三关：检查库存（防超卖核心）==========
-- 从 Redis 获取当前库存
local stock = redis.call('get', stockKey);

-- 如果库存不存在（nil），返回错误
if not stock then
    return string.format('{"%s": %d}', 'code', 10004)  -- 错误码 10004：库存数据不存在
end

-- 如果库存 ≤ 0，说明库存不足（防超卖关键判断）
if (tonumber(stock) <= 0) then
    return string.format('{"%s": %d}', 'code', 10005)  -- 错误码 10005：库存不足
end

-- ========== 第四关：检查一人一单 ==========
-- SISMEMBER：判断用户是否在已购买 Set 中
-- 返回 1 表示已存在（重复购买），返回 0 表示不存在
if (redis.call('sismember', seckillUserKey, userId) == 1) then
    return string.format('{"%s": %d}', 'code', 10006)  -- 错误码 10006：不能重复下单
end

-- ========== 第五关：执行扣减（原子操作）==========
-- 计算库存变化
local beforeQty = tonumber(stock)     -- 扣减前库存
local changeQty = 1                   -- 扣减数量（固定为 1）
local afterQty = beforeQty - changeQty -- 扣减后库存

-- 原子性扣减库存：INCRBY key -1（减 1）
redis.call('incrby', stockKey, -changeQty)

-- 将用户添加到已购买 Set 中（实现一人一单）
redis.call('sadd', seckillUserKey, userId)

-- ========== 第六关：记录追踪日志 ==========
-- 获取当前时间戳（用于日志记录）
local timeArr2 = redis.call('TIME')
local logNowMillis = tonumber(timeArr2[1]) * 1000 + math.floor(tonumber(timeArr2[2]) / 1000)

-- 构造日志条目（JSON 格式）
local logEntry = cjson.encode({
    logType = logType,              -- 日志类型：DEDUCT/RESTORE
    ts = logNowMillis,              -- 时间戳（毫秒）
    orderId = orderId,              -- 订单 ID
    traceId = traceId,              -- 追踪 ID
    userId = userId,                -- 用户 ID
    voucherId = voucherId,          -- 优惠券 ID
    beforeQty = beforeQty,          -- 扣减前库存
    changeQty = changeQty,          -- 扣减数量
    afterQty = afterQty             -- 扣减后库存
})

-- 将日志写入 Hash 结构：HSET key field value
redis.call('hset', traceLogKey, traceId, logEntry)

-- 设置追踪日志的过期时间（避免永久占用内存）
if ttlSeconds and ttlSeconds > 0 then
    redis.call('expire', traceLogKey, ttlSeconds)
end

-- ========== 返回成功结果 ==========
-- 返回 JSON 格式的成功响应，包含库存变化详情
return string.format('{"%s": %d, "%s": %s, "%s": %s, "%s": %s}',
        'code', 0,                  -- code: 0 表示成功
        'beforeQty', beforeQty,     -- 扣减前库存
        'deductQty', changeQty,     -- 扣减数量
        'afterQty', afterQty        -- 扣减后库存
)
