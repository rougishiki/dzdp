package org.javaup.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.dto.CancelVoucherOrderDto;
import org.javaup.dto.GetVoucherOrderByVoucherIdDto;
import org.javaup.dto.GetVoucherOrderDto;
import org.javaup.dto.Result;
import org.javaup.dto.VoucherReconcileLogDto;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.UserInfo;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.entity.VoucherOrderRouter;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.OrderStatus;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.exception.HmdpFrameException;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.producer.SeckillVoucherProducer;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.lua.SeckillVoucherDomain;
import org.javaup.lua.SeckillVoucherOperate;
import org.javaup.mapper.VoucherOrderMapper;
import org.javaup.mapper.VoucherOrderRouterMapper;
import org.javaup.message.MessageExtend;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCacheImpl;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.repeatexecutelimit.annotion.RepeatExecuteLimit;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IVoucherOrderRouterService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.service.IVoucherService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.RedisIdWorker;
import org.javaup.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_TOPIC;
import static org.javaup.constant.RepeatExecuteLimitConstants.SECKILL_VOUCHER_ORDER;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券订单 接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherService voucherService;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    @Resource
    private SeckillVoucherOperate seckillVoucherOperate;
    
    @Resource
    private SeckillVoucherProducer seckillVoucherProducer;
    
    @Resource
    private RedisCacheImpl redisCache;
    
    @Resource
    private IVoucherOrderRouterService voucherOrderRouterService;
    
    @Resource
    private IUserInfoService userInfoService;
    
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    
    @Resource
    private VoucherOrderRouterMapper voucherOrderRouterMapper;
    
    @Resource
    private RedisVoucherData redisVoucherData;
    
    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;
    

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public static final ThreadPoolExecutor SECKILL_ORDER_EXECUTOR =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1024),
                    new NamedThreadFactory("seckill-order-", false),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final boolean daemon;
        private final AtomicInteger index = new AtomicInteger(1);

        public NamedThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + index.getAndIncrement());
            t.setDaemon(daemon);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("未捕获异常，线程={}, err={}", thread.getName(), ex.getMessage(), ex)
            );
            return t;
        }
    }
    
    
    @PostConstruct
    private void init(){
        // 这是黑马点评的普通版本，升级版本中不再使用此方式
        //SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy(){
        try {
            SECKILL_ORDER_EXECUTOR.shutdown();
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SECKILL_ORDER_EXECUTOR.shutdownNow();
        }
    }

    private class VoucherOrderHandler implements Runnable{
        private final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 0.初始化stream
                    initStream();
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        public void initStream(){
            Boolean exists = stringRedisTemplate.hasKey(queueName);
            if (BooleanUtil.isFalse(exists)) {
                log.info("stream不存在，开始创建stream");
                // 不存在，需要创建
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("stream和group创建完毕");
                return;
            }
            // stream存在，判断group是否存在
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(queueName);
            if(groups.isEmpty()){
                log.info("group不存在，开始创建group");
                // group不存在，创建group
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("group创建完毕");
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            createVoucherOrderV1(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    IVoucherOrderService proxy;
    /**
     * 抢优惠券下单
     * */
    @Override
    public Result<Long> seckillVoucher(Long voucherId) {
        //return doSeckillVoucherV1(voucherId);
        return doSeckillVoucherV2(voucherId);
    }

    /*
    redis+Lua脚本 + 异步下单
    */
    public Result<Long> doSeckillVoucherV1(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = snowflakeIdGenerator.nextId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }
    
    public Result<Long> doSeckillVoucherV2(Long voucherId) {
        //第 1 步：查询优惠券信息，加载到Redis
        //queryByVoucherId 方法使用了5 层防护机制来避免缓存击穿
        SeckillVoucherFullModel seckillVoucherFullModel = seckillVoucherService.queryByVoucherId(voucherId);
        seckillVoucherService.loadVoucherStock(voucherId);
        //第 2 步：用户等级验证
        Long userId = UserHolder.getUser().getId();
        verifyUserLevel(seckillVoucherFullModel,userId);
        //第 3 步：生成订单 ID 和追踪 ID
        long orderId = snowflakeIdGenerator.nextId();
        long traceId = snowflakeIdGenerator.nextId();
        //第 4 步：准备 Lua 脚本的 Key 和参数
        List<String> keys = ListUtil.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey()
        );
        String[] args = new String[9];
        args[0] = voucherId.toString();
        args[1] = userId.toString();
        args[2] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getBeginTime()));
        args[3] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getEndTime()));
        args[4] = String.valueOf(seckillVoucherFullModel.getStatus());
        args[5] = String.valueOf(orderId);
        args[6] = String.valueOf(traceId);
        args[7] = String.valueOf(LogType.DEDUCT.getCode());
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), seckillVoucherFullModel.getEndTime()).getSeconds();
        long ttlSeconds = Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());
        args[8] = String.valueOf(ttlSeconds);
        //第 5 步：执行 Lua 脚本
        SeckillVoucherDomain seckillVoucherDomain = seckillVoucherOperate.execute(
                keys,
                args
        );
        //第 6 步：检查执行结果
        if (!seckillVoucherDomain.getCode().equals(BaseCode.SUCCESS.getCode())) {
            throw new HmdpFrameException(Objects.requireNonNull(BaseCode.getRc(seckillVoucherDomain.getCode())));
        }
        //第 7 步：构造 Kafka 消息
        SeckillVoucherMessage seckillVoucherMessage = new SeckillVoucherMessage(
                userId,
                voucherId,
                orderId,
                traceId,
                seckillVoucherDomain.getBeforeQty(),
                seckillVoucherDomain.getDeductQty(),
                seckillVoucherDomain.getAfterQty(),
                Boolean.FALSE
        );
        //第 8 步：发送到 Kafka
        seckillVoucherProducer.sendPayload(
                SpringUtil.getPrefixDistinctionName() + "-" + SECKILL_VOUCHER_TOPIC, 
                seckillVoucherMessage);
        //第 9 步：返回订单 ID
        return Result.ok(orderId);
    }
    
    public void verifyUserLevel(SeckillVoucherFullModel seckillVoucherFullModel,Long userId){
        String allowedLevelsStr = seckillVoucherFullModel.getAllowedLevels();
        Integer minLevel = seckillVoucherFullModel.getMinLevel();
        boolean hasLevelRule = StrUtil.isNotBlank(allowedLevelsStr) || Objects.nonNull(minLevel);
        if (!hasLevelRule) {
            return;
        }
        UserInfo userInfo = userInfoService.getByUserId(userId);
        if (Objects.isNull(userInfo)) {
            throw new HmdpFrameException(BaseCode.USER_NOT_EXIST);
        }
        boolean allowed = true;
        Integer level = userInfo.getLevel();
        if (StrUtil.isNotBlank(allowedLevelsStr)) {
            try {
                Set<Integer> allowedLevels = Arrays.stream(allowedLevelsStr.split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .map(Integer::valueOf)
                        .collect(Collectors.toSet());
                if (CollectionUtil.isNotEmpty(allowedLevels)) {
                    allowed = allowedLevels.contains(level);
                }
            } catch (Exception parseEx) {
                log.warn("allowedLevels 解析失败, voucherId={}, raw={}",
                        seckillVoucherFullModel.getVoucherId(), 
                        allowedLevelsStr, parseEx);
            }
        }
        if (allowed && Objects.nonNull(minLevel)) {
            allowed = Objects.nonNull(level) && level >= minLevel;
        }
        if (!allowed) {
            throw new HmdpFrameException("当前会员级别不满足参与条件");
        }
    }

   
    private static class AudienceRule {
        public Set<Integer> allowedLevels;
        public Integer minLevel;
        public Set<String> allowedCities;
        
        boolean hasLevelRule(){
            return (allowedLevels != null && !allowedLevels.isEmpty()) || minLevel != null;
        }
        boolean hasCityRule(){
            return allowedCities != null && !allowedCities.isEmpty();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrderV1(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1.查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                // set stock = stock - 1
                .setSql("stock = stock - 1")
                // where id = ? and stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) 
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        // 7.创建订单
        save(voucherOrder);
    }
    
    
    /**
     * 创建秒杀优惠券订单（V2 版本，基于 Kafka 消息队列）
     * 
     * 核心流程：
     * 1. 幂等性检查：通过 @RepeatExecuteLimit 注解和数据库唯一键防止重复下单
     * 2. 库存扣减：使用 SQL 原子操作 stock = stock - 1，避免超卖
     * 3. 订单创建：保存订单记录到数据库
     * 4. 路由表记录：用于后续分库分表查询优化
     * 5. Redis 缓存：短期缓存订单信息（60 秒），加速查询
     * 6. 对账日志：记录扣减操作，便于后续数据一致性校验
     * 
     * @param message Kafka 消息，包含用户 ID、优惠券 ID、订单 ID 等信息
     * @return true=创建成功
     * @throws HmdpFrameException 当订单已存在或库存不足时抛出异常
     */
    @Override
    @RepeatExecuteLimit(name = SECKILL_VOUCHER_ORDER,keys = {"#message.uuid"})
    @Transactional(rollbackFor = Exception.class)
    public boolean createVoucherOrderV2(MessageExtend<SeckillVoucherMessage> message) {
        // 解析消息体，获取用户 ID 和优惠券信息
        SeckillVoucherMessage messageBody = message.getMessageBody();
        Long userId = messageBody.getUserId();
        // 检查是否已存在正常状态的订单（一人一单限制）
        VoucherOrder normalVoucherOrder = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, messageBody.getVoucherId())
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus,OrderStatus.NORMAL.getCode())
                .one();
        if (Objects.nonNull(normalVoucherOrder)) {
            log.warn("已存在此订单，voucherId：{},userId：{}", normalVoucherOrder.getVoucherId(), userId);
            // 抛出订单已存在异常，触发库存回滚
            throw new HmdpFrameException(BaseCode.VOUCHER_ORDER_EXIST);
        }
        // 扣减库存：使用 SQL 原子操作，stock = stock - 1，条件 stock > 0
        // 这种写法能保证在高并发下不会超卖，利用数据库行锁保证线程安全
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", messageBody.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败说明库存不足
            throw new HmdpFrameException("优惠券库存不足！优惠券 id:" + messageBody.getVoucherId());
        }
        // 创建订单记录
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(messageBody.getOrderId());
        voucherOrder.setUserId(messageBody.getUserId());
        voucherOrder.setVoucherId(messageBody.getVoucherId());
        voucherOrder.setCreateTime(LocalDateTimeUtil.now());
        save(voucherOrder);
        // 创建订单路由表记录，用于后续分库分表场景下的快速查询
        VoucherOrderRouter voucherOrderRouter = new VoucherOrderRouter();
        voucherOrderRouter.setId(snowflakeIdGenerator.nextId());
        voucherOrderRouter.setOrderId(voucherOrder.getId());
        voucherOrderRouter.setUserId(userId);
        voucherOrderRouter.setVoucherId(voucherOrder.getVoucherId());
        voucherOrderRouter.setCreateTime(LocalDateTimeUtil.now());
        voucherOrderRouter.setUpdateTime(LocalDateTimeUtil.now());
        voucherOrderRouterService.save(voucherOrderRouter);
        // 将订单信息缓存到 Redis，Key: db:seckill:order:{orderId}，有效期 60 秒
        // 用于加速后续的订单查询请求，减少数据库压力
        redisCache.set(RedisKeyBuild.createRedisKey(
                RedisKeyManage.DB_SECKILL_ORDER_KEY,messageBody.getOrderId()),
                voucherOrder,
                60, 
                TimeUnit.SECONDS
        );
        // 保存对账日志，记录本次成功的扣减操作
        // 用于后续数据一致性检查和修复
        voucherReconcileLogService.saveReconcileLog(
                LogType.DEDUCT.getCode(),
                BusinessType.SUCCESS.getCode(),
                "order created",
                message
        );
        return true;
    }
    
    @Override
    public Long getSeckillVoucherOrder(GetVoucherOrderDto getVoucherOrderDto) {
        VoucherOrder voucherOrder = 
                redisCache.get(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.DB_SECKILL_ORDER_KEY, 
                        getVoucherOrderDto.getOrderId()), 
                        VoucherOrder.class);
        if (Objects.nonNull(voucherOrder)) {
            return voucherOrder.getId();
        }
        VoucherOrderRouter voucherOrderRouter = 
                voucherOrderRouterService.lambdaQuery()
                        .eq(VoucherOrderRouter::getOrderId, getVoucherOrderDto.getOrderId())
                        .one();
        if (Objects.nonNull(voucherOrderRouter)) {
            return voucherOrderRouter.getOrderId();
        }
        return null;
    }
    
    @Override
    public Long getSeckillVoucherOrderIdByVoucherId(GetVoucherOrderByVoucherIdDto getVoucherOrderByVoucherIdDto) {
        VoucherOrder voucherOrder = lambdaQuery()
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, getVoucherOrderByVoucherIdDto.getVoucherId())
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())
                .one();
        if (Objects.nonNull(voucherOrder)) {
            return voucherOrder.getId();
        }
        return null;
    }
    
    /**
     * 取消秒杀优惠券订单
     * 
     * 业务流程：
     * 1. 验证订单是否存在且状态正常
     * 2. 更新订单状态为已取消
     * 3. 恢复秒杀库存（+1）
     * 4. 记录对账日志
     * 5. 回滚 Redis 中的库存数据
     * 6. 清理用户订阅状态
     * 7. 扣减店铺买家排行榜数据
     * 8. 自动发券给候补队列中的下一个用户
     *
     * @param cancelVoucherOrderDto 取消订单请求参数（包含 voucherId）
     * @return Boolean 取消成功返回 true，失败返回 false
     * @throws HmdpFrameException 当订单不存在或优惠券不存在时抛出异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancel(CancelVoucherOrderDto cancelVoucherOrderDto) {
        // 1. 查询待取消的订单（必须是当前用户的、未使用的订单）
        VoucherOrder voucherOrder = lambdaQuery()
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())
                .one();
        if (Objects.isNull(voucherOrder)) {
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_ORDER_NOT_EXIST);
        }
        
        // 2. 查询秒杀优惠券信息，用于后续库存恢复
        SeckillVoucher seckillVoucher = seckillVoucherService.lambdaQuery()
                .eq(SeckillVoucher::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .one();
        if (Objects.isNull(seckillVoucher)) {
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_NOT_EXIST);
        }
        
        // 3. 更新订单状态为已取消
        boolean updateResult = lambdaUpdate().set(VoucherOrder::getStatus, OrderStatus.CANCEL.getCode())
                .set(VoucherOrder::getUpdateTime, LocalDateTimeUtil.now())
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .update();
        
        // 4. 生成全局唯一追踪 ID，用于链路追踪和对账
        long traceId = snowflakeIdGenerator.nextId();
        
        // 5. 构建对账日志 DTO，记录库存恢复详情
        VoucherReconcileLogDto voucherReconcileLogDto = new VoucherReconcileLogDto();
        voucherReconcileLogDto.setOrderId(voucherOrder.getId());
        voucherReconcileLogDto.setUserId(voucherOrder.getUserId());
        voucherReconcileLogDto.setVoucherId(voucherOrder.getVoucherId());
        voucherReconcileLogDto.setDetail("cancel voucher order ");
        voucherReconcileLogDto.setBeforeQty(seckillVoucher.getStock());  // 恢复前库存
        voucherReconcileLogDto.setChangeQty(1);                          // 变化量（+1）
        voucherReconcileLogDto.setAfterQty(seckillVoucher.getStock() + 1); // 恢复后库存
        voucherReconcileLogDto.setTraceId(traceId);
        voucherReconcileLogDto.setLogType(LogType.RESTORE.getCode());      // 日志类型：库存恢复
        voucherReconcileLogDto.setBusinessType( BusinessType.CANCEL.getCode()); // 业务类型：取消订单
        
        // 6. 保存对账日志到数据库
        boolean saveReconcileLogResult = voucherReconcileLogService.saveReconcileLog(voucherReconcileLogDto);
        
        // 7. 恢复秒杀库存（stock + 1）
        boolean rollbackStockResult = seckillVoucherService.rollbackStock(cancelVoucherOrderDto.getVoucherId());
        
        // 8. 汇总所有操作结果
        Boolean result = updateResult && saveReconcileLogResult && rollbackStockResult;
        if (result) {
            // 9. 回滚 Redis 中的库存数据（Lua 脚本保证原子性）
            redisVoucherData.rollbackRedisVoucherData(
                    SeckillVoucherOrderOperate.YES,
                    traceId,
                    voucherOrder.getVoucherId(),
                    voucherOrder.getUserId(),
                    voucherOrder.getId(),
                    seckillVoucher.getStock(),
                    1,
                    seckillVoucher.getStock() + 1
            );
            
            // 10. 删除用户的订阅状态缓存
            redisCache.delForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, 
                    cancelVoucherOrderDto.getVoucherId()),
                    String.valueOf(voucherOrder.getUserId()));
            
            // 11. 获取优惠券信息，用于处理店铺排行榜
            Voucher voucher = voucherService.getById(voucherOrder.getVoucherId());
            if (Objects.nonNull(voucher)) {
                // 格式化日期为 yyyyMMdd 格式
                String day = voucherOrder.getCreateTime().format(DateTimeFormatter.BASIC_ISO_DATE);
                // 构建店铺日榜 Redis Key
                RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        voucher.getShopId(),
                        day
                );
                // 扣减用户在店铺日榜中的购买次数（-1）
                redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(voucherOrder.getUserId()), -1.0);
            }
            
            // 12. 尝试自动发券给候补队列中的下一个用户（异步非阻塞）
            try {
                autoIssueVoucherToEarliestSubscriber(
                        voucherOrder.getVoucherId(), 
                        voucherOrder.getUserId()
                );
            } catch (Exception e) {
                // 发券失败不影响主流程，仅记录警告日志
                log.warn("自动发券失败，voucherId={}, err=\n{}", voucherOrder.getVoucherId(), e.getMessage());
            }
        }
        return result;
    }
    
    /**
     * 自动发券给候补队列中最早的订阅用户
     * 
     * 业务场景：
     * 当有用户取消订单后，系统自动将释放的库存发放给候补队列中的下一个用户，
     * 提高秒杀券的利用率，减少用户等待时间。
     * 
     * 核心流程：
     * 1. 查询秒杀优惠券的完整信息（包括库存、时间等）
     * 2. 验证优惠券是否存在且在有效期内
     * 3. 加载库存信息到 Redis
     * 4. 从候补队列中查找最早的候选用户
     * 5. 向候选用户发券
     *
     * @param voucherId 优惠券 ID
     * @param excludeUserId 排除的用户 ID（通常是取消订单的用户，避免重复发券）
     * @return boolean 发券成功返回 true，失败返回 false（无候补用户、库存不足等情况）
     */
    @Override
    public boolean autoIssueVoucherToEarliestSubscriber(final Long voucherId, final Long excludeUserId) {
        // 1. 查询秒杀优惠券的完整模型（包含库存、时间等全量信息）
        SeckillVoucherFullModel seckillVoucherFullModel = seckillVoucherService.queryByVoucherId(voucherId);
        if (Objects.isNull(seckillVoucherFullModel) 
                || Objects.isNull(seckillVoucherFullModel.getBeginTime()) 
                || Objects.isNull(seckillVoucherFullModel.getEndTime())) {
            // 优惠券不存在或时间信息不完整，无法发券
            return false;
        }
        
        // 2. 加载库存信息到 Redis 缓存（为后续 Lua 脚本做准备）
        seckillVoucherService.loadVoucherStock(voucherId);
        
        // 3. 从候补队列中查找最早的候选用户（排除指定用户）
        // 候补队列基于 Redis ZSET 实现，score 为订阅时间戳，按时间顺序排队
        String candidateUserIdStr = findEarliestCandidate(voucherId, excludeUserId);
        if (StrUtil.isBlank(candidateUserIdStr)) {
            // 没有符合条件的候补用户
            return false;
        }
        
        // 4. 向候选用户发放优惠券（包含库存扣减、订单创建等操作）
        return issueToCandidate(voucherId, candidateUserIdStr, seckillVoucherFullModel);
    }
    
    private String findEarliestCandidate(final Long voucherId, final Long excludeUserId) {
        RedisKeyBuild subscribeZSetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY, voucherId);
        RedisKeyBuild purchasedSetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId);
        
        final long pageCount = 1L;
        long offset = 0L;
        while (true) {
            Set<ZSetOperations.TypedTuple<String>> page = redisCache.rangeByScoreWithScoreForSortedSet(
                    subscribeZSetKey,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    offset,
                    pageCount,
                    String.class
            );
            if (CollectionUtil.isEmpty(page)) {
                return null;
            }
            ZSetOperations.TypedTuple<String> tuple = page.iterator().next();
            if (Objects.isNull(tuple) || Objects.isNull(tuple.getValue())) {
                offset++;
                continue;
            }
            String uidStr = tuple.getValue();
            if (StrUtil.isBlank(uidStr)) {
                offset++;
                continue;
            }
            if (Objects.nonNull(excludeUserId) && Objects.equals(uidStr, String.valueOf(excludeUserId))) {
                offset++;
                continue;
            }
            Boolean purchased = redisCache.isMemberForSet(purchasedSetKey, uidStr);
            if (BooleanUtil.isTrue(purchased)) {
                offset++;
                continue;
            }
            return uidStr;
        }
    }
    
    private boolean issueToCandidate(final Long voucherId, 
                                     final String candidateUserIdStr, 
                                     final SeckillVoucherFullModel seckillVoucherFullModel) {
        Long candidateUserId = Long.valueOf(candidateUserIdStr);
        try {
            verifyUserLevel(seckillVoucherFullModel, candidateUserId);
        } catch (Exception e) {
            log.info("候选用户不满足人群规则，自动发券跳过。voucherId={}, userId={}", voucherId, candidateUserId);
            return false;
        }
        List<String> keys = buildSeckillKeys(voucherId);
        long orderId = snowflakeIdGenerator.nextId();
        long traceId = snowflakeIdGenerator.nextId();
        String[] args = buildSeckillArgs(voucherId, candidateUserIdStr, seckillVoucherFullModel, orderId, traceId);
        SeckillVoucherDomain domain = seckillVoucherOperate.execute(keys, args);
        if (!Objects.equals(domain.getCode(), BaseCode.SUCCESS.getCode())) {
            log.info("自动发券Lua扣减失败，code={}, voucherId={}, userId={}", domain.getCode(), voucherId, candidateUserId);
            return false;
        }
        SeckillVoucherMessage message = new SeckillVoucherMessage(
                candidateUserId,
                voucherId,
                orderId,
                traceId,
                domain.getBeforeQty(),
                domain.getDeductQty(),
                domain.getAfterQty(),
                Boolean.TRUE
        );
        seckillVoucherProducer.sendPayload(
                SpringUtil.getPrefixDistinctionName() + "-" + SECKILL_VOUCHER_TOPIC,
                message
        );
        return true;
    }
    
    private List<String> buildSeckillKeys(final Long voucherId) {
        String stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey();
        String userKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey();
        String traceKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey();
        return ListUtil.of(stockKey, userKey, traceKey);
    }
    
    private String[] buildSeckillArgs(final Long voucherId,
                                      final String userIdStr,
                                      final SeckillVoucherFullModel seckillVoucherFullModel,
                                      final long orderId,
                                      final long traceId) {
        String[] args = new String[9];
        args[0] = voucherId.toString();
        args[1] = userIdStr;
        args[2] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getBeginTime()));
        args[3] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getEndTime()));
        args[4] = String.valueOf(seckillVoucherFullModel.getStatus());
        args[5] = String.valueOf(orderId);
        args[6] = String.valueOf(traceId);
        args[7] = String.valueOf(LogType.DEDUCT.getCode());
        args[8] = String.valueOf(computeTtlSeconds(seckillVoucherFullModel));
        return args;
    }
    
    private long computeTtlSeconds(final SeckillVoucherFullModel seckillVoucherFullModel) {
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), seckillVoucherFullModel.getEndTime()).getSeconds();
        return Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());
    }
}
