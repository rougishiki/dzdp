package org.javaup.delay.consumer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.ConsumerTask;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedVoucherReminderMessage;
import org.javaup.entity.UserInfo;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IUserInfoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.DELAY_VOUCHER_REMINDER;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 延迟抢购优惠券提醒-消费
 * @author: 阿星不是程序员
 **/

@Slf4j
@Component
public class ConsumerDelayedVoucherReminder implements ConsumerTask {
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private IUserInfoService userInfoService;


    @Value("${seckill.reminder.notify.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${seckill.reminder.notify.app.enabled:false}")
    private boolean appEnabled;

    @Value("${seckill.reminder.notify.sms.to:}")
    private String smsTo;

    @Value("${seckill.reminder.dedup.window.seconds:1800}")
    private long dedupWindowSeconds;

    /**
     * 当优惠券未设置allowedLevels/minLevel时的默认最小会员等级
     * */
    @Value("${seckill.reminder.notify.default.minLevel:1}")
    private int defaultMinLevel;
    /**
     * 每次提醒的最大用户数量，防止一次性通知过多
     * */
    @Value("${seckill.reminder.notify.max.users:1000}")
    private int maxNotifyUsers;
    /**
     * 是否附加通知“最近购买活跃用户”
     * */
    @Value("${seckill.reminder.notify.top.buyers.enabled:true}")
    private boolean topBuyersEnabled;
    /**
     * 统计最近多少天的购买行为
     * */
    @Value("${seckill.reminder.notify.top.buyers.days:30}")
    private int topBuyersDays;
    /**
     * Top购买用户数量
     * */
    @Value("${seckill.reminder.notify.top.buyers.count:200}")
    private int topBuyersCount;
    /**
     * 最大会员等级
     */
    @Value("${seckill.reminder.notify.user.level.max:10}")
    private int maxUserLevel;

    @Override
    public void execute(final String content) {
        try {
            // 1️⃣ 解析延迟队列传来的消息（JSON 格式）
            DelayedVoucherReminderMessage msg = parseMessage(content);
            if (Objects.isNull(msg)) {
                return;
            }

            // 2️⃣ 从消息中获取优惠券 ID
            Long voucherId = msg.getVoucherId();

            // 3️⃣ 查询优惠券本身的完整信息（包括店铺、会员等级限制等）
            SeckillVoucherFullModel voucherFull = seckillVoucherService.queryByVoucherId(voucherId);
            if (voucherFull == null) {
                // 如果优惠券不存在或缓存未命中，记录警告并退出
                log.warn("[DELAY_REMINDER_CONSUMER] 秒杀券不存在或缓存未命中 voucherId={}", voucherId);
                return;
            }

            // 4️⃣ 【核心】构建要通知的用户列表
            //    根据会员等级、关注店铺、购买历史等条件筛选目标用户
            Set<String> userIds = buildAudienceUserIds(voucherFull);
            if (CollectionUtil.isEmpty(userIds)) {
                // 如果没有符合规则的用户，记录日志并退出
                log.info("[DELAY_REMINDER_CONSUMER] 无符合规则的用户 voucherId={}", voucherId);
                return;
            }

            // 5️⃣ 【核心】循环遍历所有用户，逐个发送通知
            //    包含去重检查、短信通知、APP 推送等逻辑
            int notified = notifyUsers(voucherId, msg.getBeginTime(), userIds);

            // 6️⃣ 记录执行结果：优惠券 ID、总用户数、实际通知人数
            log.info("[DELAY_REMINDER_CONSUMER] 完成提醒 voucherId={} totalUsers={} notified={}",
                    voucherId, userIds.size(), notified);
        } catch (Exception e) {
            // 捕获所有异常，防止单个任务失败影响其他任务
            log.warn("[DELAY_REMINDER_CONSUMER] 执行异常", e);
        }
    }


    private DelayedVoucherReminderMessage parseMessage(String content) {
        try {
            DelayedVoucherReminderMessage msg = JSON.parseObject(content, DelayedVoucherReminderMessage.class);
            if (msg == null || msg.getVoucherId() == null) {
                log.warn("[DELAY_REMINDER_CONSUMER] 消息解析失败 content={}", content);
                return null;
            }
            return msg;
        } catch (Exception ex) {
            log.warn("[DELAY_REMINDER_CONSUMER] 消息反序列化异常 content={}", content, ex);
            return null;
        }
    }

    private Set<String> buildAudienceUserIds(SeckillVoucherFullModel voucherFull) {
        String allowedLevelsStr = voucherFull.getAllowedLevels();
        Integer minLevel = voucherFull.getMinLevel();
        Long shopId = voucherFull.getShopId();
        List<UserInfo> userInfos = queryEligibleUserInfos(allowedLevelsStr, minLevel);
        Set<String> userIds = toUserIdSet(userInfos);
        if (topBuyersEnabled && Objects.nonNull(shopId)) {
            for (Long uid : readTopBuyersFromRedis(shopId, topBuyersCount, topBuyersDays)) {
                if (uid != null) { 
                    userIds.add(String.valueOf(uid)); 
                }
            }
        } else if (topBuyersEnabled) {
            log.warn("[DELAY_REMINDER_CONSUMER] 店铺ID为空，跳过Top买家统计");
        }
        return userIds;
    }

    private List<UserInfo> queryEligibleUserInfos(String allowedLevelsStr, Integer minLevel) {
        if (StrUtil.isNotBlank(allowedLevelsStr)) {
            Set<Integer> allowed = parseAllowedLevels(allowedLevelsStr);
            if (CollectionUtil.isNotEmpty(allowed)) {
                List<Long> fromRedis = readUserIdsFromLevelSets(new ArrayList<>(allowed), maxNotifyUsers);
                if (CollectionUtil.isNotEmpty(fromRedis)) {
                    List<UserInfo> list = new ArrayList<>(fromRedis.size());
                    for (Long uid : fromRedis) { 
                        if (uid != null) { 
                            UserInfo u = new UserInfo(); 
                            u.setUserId(uid); 
                            list.add(u);
                        } 
                    }
                    return list;
                }
                return userInfoService.lambdaQuery()
                        .select(UserInfo::getUserId, UserInfo::getLevel)
                        .in(UserInfo::getLevel, allowed)
                        .last("limit " + maxNotifyUsers)
                        .list();
            }
            int useMin = Objects.nonNull(minLevel) ? minLevel : defaultMinLevel;
            List<Long> fromRedis = readUserIdsFromLevelSets(buildLevelRange(useMin, maxUserLevel), maxNotifyUsers);
            if (CollectionUtil.isNotEmpty(fromRedis)) {
                List<UserInfo> list = new ArrayList<>(fromRedis.size());
                for (Long uid : fromRedis) { 
                    if (uid != null) {
                        UserInfo u = new UserInfo(); 
                        u.setUserId(uid); 
                        list.add(u);
                    } 
                }
                return list;
            }
            return userInfoService.lambdaQuery()
                    .select(UserInfo::getUserId, UserInfo::getLevel)
                    .ge(UserInfo::getLevel, useMin)
                    .last("limit " + maxNotifyUsers)
                    .list();
        }
        if (Objects.nonNull(minLevel)) {
            List<Long> fromRedis = readUserIdsFromLevelSets(buildLevelRange(minLevel, maxUserLevel), maxNotifyUsers);
            if (CollectionUtil.isNotEmpty(fromRedis)) {
                List<UserInfo> list = new ArrayList<>(fromRedis.size());
                for (Long uid : fromRedis) { 
                    if (uid != null) { 
                        UserInfo u = new UserInfo(); 
                        u.setUserId(uid); list.add(u);
                    } 
                }
                return list;
            }
            return userInfoService.lambdaQuery()
                    .select(UserInfo::getUserId, UserInfo::getLevel)
                    .ge(UserInfo::getLevel, minLevel)
                    .last("limit " + maxNotifyUsers)
                    .list();
        }
        List<Long> fromRedis = readUserIdsFromLevelSets(buildLevelRange(defaultMinLevel, maxUserLevel), maxNotifyUsers);
        if (CollectionUtil.isNotEmpty(fromRedis)) {
            List<UserInfo> list = new ArrayList<>(fromRedis.size());
            for (Long uid : fromRedis) { 
                if (uid != null) { 
                    UserInfo u = new UserInfo(); 
                    u.setUserId(uid); 
                    list.add(u);
                } 
            }
            return list;
        }
        return userInfoService.lambdaQuery()
                .select(UserInfo::getUserId, UserInfo::getLevel)
                .ge(UserInfo::getLevel, defaultMinLevel)
                .last("limit " + maxNotifyUsers)
                .list();
    }

    private List<Integer> buildLevelRange(int min, int max) {
        int from = Math.max(min, 1);
        int to = Math.max(max, from);
        List<Integer> levels = new ArrayList<>(to - from + 1);
        for (int lv = from; lv <= to; lv++) { 
            levels.add(lv); 
        }
        return levels;
    }

    private List<Long> readUserIdsFromLevelSets(List<Integer> levels, int count) {
        if (CollectionUtil.isEmpty(levels)) { 
            return Collections.emptyList(); 
        }
        List<RedisKeyBuild> keys = new ArrayList<>(levels.size());
        for (Integer lv : levels) {
            if (lv == null) { 
                continue; 
            }
            keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_LEVEL_MEMBERS_TAG_KEY, lv));
        }
        if (keys.isEmpty()) { 
            return Collections.emptyList();
        }
        if (keys.size() == 1) {
            Set<Long> r = redisCache.distinctRandomMembersForSet(keys.get(0), Math.max(count, 1), Long.class);
            return new ArrayList<>(r);
        }
        String label;
        if (levels.size() >= 2) { 
            label = levels.get(0) + "-" + levels.get(levels.size()-1); 
        } else { 
            label = String.valueOf(levels.get(0)); 
        }
        RedisKeyBuild dest = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_LEVEL_MEMBERS_UNION_TAG_KEY, label);
        RedisKeyBuild base = keys.get(0);
        Collection<RedisKeyBuild> others = keys.subList(1, keys.size());
        try {
            redisCache.unionAndStoreForSet(base, others, dest);
            redisCache.expire(dest, 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[DELAY_REMINDER_CONSUMER] SET并集失败 levels={} label={}", levels, label, e);
        }
        Set<Long> r = redisCache.distinctRandomMembersForSet(dest, Math.max(count, 1), Long.class);
        return new ArrayList<>(r);
    }

    private Set<Integer> parseAllowedLevels(String allowedLevelsStr) {
        Set<Integer> allowed = new HashSet<>();
        if (StrUtil.isBlank(allowedLevelsStr)) { return allowed; }
        String[] parts = allowedLevelsStr.split(",");
        for (String s : parts) {
            if (StrUtil.isNotBlank(s)) {
                try { 
                    allowed.add(Integer.valueOf(s.trim())); 
                } catch (Exception ignore) {
                    
                }
            }
        }
        return allowed;
    }

    private Set<String> toUserIdSet(List<UserInfo> userInfos) {
        Set<String> userIds = new LinkedHashSet<>();
        if (CollectionUtil.isEmpty(userInfos)) { return userIds; }
        for (UserInfo ui : userInfos) {
            if (Objects.nonNull(ui) && Objects.nonNull(ui.getUserId())) {
                userIds.add(String.valueOf(ui.getUserId()));
            }
        }
        return userIds;
    }

    private List<Long> readTopBuyersFromRedis(Long shopId, int count, int days) {
        try {
            LocalDate today = LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.BASIC_ISO_DATE;
            List<RedisKeyBuild> dailyKeys = new ArrayList<>();
            for (int i = 0; i < Math.max(days, 1); i++) {
                String day = today.minusDays(i).format(fmt);
                dailyKeys.add(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        shopId,
                        day
                ));
            }
            if (dailyKeys.isEmpty()) { 
                return Collections.emptyList(); 
            }
            if (dailyKeys.size() == 1) {
                Set<Long> topSet = redisCache.getReverseRangeForSortedSet(
                        dailyKeys.get(0), 0, Math.max(count - 1, 0), Long.class);
                return new ArrayList<>(topSet);
            }
            String rangeLabel = today.minusDays(dailyKeys.size() - 1).format(fmt) + "-" + today.format(fmt);
            RedisKeyBuild destKey = RedisKeyBuild.createRedisKey(
                    RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_UNION_TAG_KEY,
                    shopId,
                    rangeLabel
            );
            RedisKeyBuild base = dailyKeys.get(0);
            Collection<RedisKeyBuild> others = dailyKeys.subList(1, dailyKeys.size());
            try {
                redisCache.unionAndStoreForSortedSet(base, others, destKey);
                redisCache.expire(destKey, 60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[DELAY_REMINDER_CONSUMER] ZSET并集失败 shopId={} range={}", shopId, rangeLabel, e);
            }
            Set<Long> topSet = redisCache.getReverseRangeForSortedSet(destKey, 0, Math.max(count - 1, 0), Long.class);
            return new ArrayList<>(topSet);
        } catch (Exception ex) {
            log.warn("[DELAY_REMINDER_CONSUMER] 读取Redis Top买家失败 shopId={} days={} count={} ex={}",
                    shopId, days, count, ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 循环遍历所有用户，逐个发送提醒通知
     * @param voucherId 优惠券 ID
     * @param beginTime 活动开始时间
     * @param userIds 要通知的用户 ID 集合（可能有几千人）
     * @return 实际成功通知的用户数量
     */
    private int notifyUsers(Long voucherId, LocalDateTime beginTime, Set<String> userIds) {
        int notifyCount = 0;  // 统计实际通知的人数
        // 🔥【核心循环】遍历每个用户，逐个发送通知
        for (String userIdStr : userIds) {
            // 跳过空的用户 ID
            if (StrUtil.isBlank(userIdStr)) { continue; }
            // 1️⃣ 【去重检查】防止重复通知同一个用户
            boolean shouldNotify;
            try {
                // 使用 Redis 的 setIfAbsent (SETNX) 实现去重
                // key: seckill:reminder:notify:dedup:{voucherId}:{userId}
                // value: "1"
                // expire: dedupWindowSeconds (默认 1800 秒 = 30 分钟)
                // 如果返回 true，说明是第一次通知；如果返回 false，说明 30 分钟内已经通知过了
                shouldNotify = redisCache.setIfAbsent(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_REMINDER_NOTIFY_DEDUP_KEY, voucherId, userIdStr),
                        "1",
                        dedupWindowSeconds,
                        java.util.concurrent.TimeUnit.SECONDS
                );
            } catch (Exception e) {
                // 如果 Redis 操作失败（比如网络问题），默认允许通知，避免漏发
                shouldNotify = true;
            }
            // 2️⃣ 如果 30 分钟内已经通知过这个用户，跳过
            if (!shouldNotify) {
                continue;
            }
            // 3️⃣ 构造通知内容
            String notifyContent = String.format("[REMINDER] voucherId=%s userId=%s beginTime=%s",
                    voucherId, userIdStr, beginTime);
            // 4️⃣ 【发送短信通知】（如果开启了短信功能）
            if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
                // 示例日志：[REMINDER_SMS] to=13800138000 content=[REMINDER] voucherId=123 userId=1001 beginTime=2024-03-15 10:00:00
                log.info("[REMINDER_SMS] to={} content={}", smsTo, notifyContent);
            }
            // 5️⃣ 【发送 APP 推送通知】（如果开启了 APP 推送功能）
            if (appEnabled) {
                // 示例日志：[REMINDER_APP] userId=1001 content=[REMINDER] voucherId=123 userId=1001 beginTime=2024-03-15 10:00:00
                log.info("[REMINDER_APP] userId={} content={}", userIdStr, notifyContent);
            }
            // 6️⃣ 成功通知一人，计数器 +1
            notifyCount++;
        }
        // 7️⃣ 返回实际通知的人数
        return notifyCount;
    }

    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_VOUCHER_REMINDER;
    }
}
