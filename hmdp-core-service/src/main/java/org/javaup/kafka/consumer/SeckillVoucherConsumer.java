package org.javaup.kafka.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.exception.HmdpFrameException;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.message.MessageExtend;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IAutoIssueNotifyService;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 消费者：处理秒杀券下单消息。
 * @author: 阿星不是程序员
 **/

@Slf4j
@Component
public class SeckillVoucherConsumer extends AbstractConsumerHandler<SeckillVoucherMessage> {
    
    public static Long MESSAGE_DELAY_TIME = 10000L;
    
    @Resource
    private IVoucherOrderService voucherOrderService;
    
    @Resource
    private RedisVoucherData redisVoucherData;
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;
     
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    
    @Resource
    private IAutoIssueNotifyService autoIssueNotifyService;
    
    
    // 获取 CPU 核心数，用于配置线程池参数
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    // 线程池核心线程数：至少 2 个线程，否则使用 CPU 核心数
    private static final int EXECUTOR_THREADS = Math.max(2, CPU_CORES);
    // 线程池队列容量：1024 * CPU 核心数，至少为 1024
    private static final int EXECUTOR_QUEUE_CAPACITY = 1024 * Math.max(1, CPU_CORES);
    
    /**
     * 秒杀订单消费任务专用线程池
     * 用于异步执行消费成功后的后续处理任务（如清理订阅、发券通知、统计买家等）
     * 采用固定大小线程池，拒绝策略为调用者运行，避免任务丢失
     */
    private static final ThreadPoolExecutor SECKILL_ORDER_CONSUME_TASK_EXECUTOR =
            new ThreadPoolExecutor(
                    EXECUTOR_THREADS,
                    EXECUTOR_THREADS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                    new NamedThreadFactory("seckill-order-consume-task", false),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
    
    /**
     * 自定义线程工厂
     * 功能：
     * 1. 为线程命名，格式为 "namePrefix + 序号"
     * 2. 设置是否为守护线程
     * 3. 统一设置未捕获异常处理器，记录错误日志
     */
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
            // 创建线程并设置名称
            Thread t = new Thread(r, namePrefix + index.getAndIncrement());
            // 设置是否为守护线程
            t.setDaemon(daemon);
            // 设置未捕获异常处理器，记录详细错误信息
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("未捕获异常，线程={}, err={}", thread.getName(), ex.getMessage(), ex)
            );
            return t;
        }
    }
    
    
    public SeckillVoucherConsumer() {
        super(SeckillVoucherMessage.class);
    }
    
   
    /**
     * Kafka 消息监听方法
     * 监听指定 topic 的秒杀优惠券消息
     * 
     * @param value 消息体内容（JSON 字符串）
     * @param headers 消息头信息
     * @param key 消息分区键（可选）
     * @param acknowledgment Kafka 确认对象，用于手动提交偏移量
     */
    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SECKILL_VOUCHER_TOPIC}
    )
    public void onMessage(String value,
                          @Headers Map<String, Object> headers,
                          @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                          Acknowledgment acknowledgment) {
        // 解析并消费原始消息
        consumeRaw(value, key, headers);
        // 手动提交 Kafka 偏移量，确认消息已被处理
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }
    
    /**
     * 消费前预处理：检查消息延迟时间
     * 如果消息从生产到消费的时间超过阈值（10 秒），则丢弃该消息并回滚 Redis 库存
     * 
     * @param message 封装后的消息对象
     * @return true=继续消费，false=丢弃消息
     */
    @Override
    protected Boolean beforeConsume(MessageExtend<SeckillVoucherMessage> message) {
        // 计算消息延迟时间：当前时间 - 生产者发送时间
        long producerTimeTimestamp = message.getProducerTime().getTime();
        long delayTime = System.currentTimeMillis() - producerTimeTimestamp;
        //如果消息超时时间达到了阈值（10 秒）
        if (delayTime > MESSAGE_DELAY_TIME){
            log.info("消费到 kafka 的创建优惠券消息延迟时间大于了 {} 毫秒 此订单消息被丢弃 订单号 : {}",
                    delayTime,message.getMessageBody().getOrderId());
            // 生成全局唯一追踪 ID，用于对账日志
            long traceId = snowflakeIdGenerator.nextId();
            // 回滚 Redis 中的库存数据（恢复扣减前的状态）
            redisVoucherData.rollbackRedisVoucherData(
                    SeckillVoucherOrderOperate.YES,
                    traceId,
                    message.getMessageBody().getVoucherId(),
                    message.getMessageBody().getUserId(),
                    message.getMessageBody().getOrderId(),
                    // 这是回滚操作，所以redis中扣减前和扣减后的数量要和消息中的反过来
                    message.getMessageBody().getAfterQty(),
                    message.getMessageBody().getChangeQty(),
                    message.getMessageBody().getBeforeQty()
            );
            try {
                // 保存对账日志，记录因消息延迟而回滚的操作
                voucherReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(), 
                        BusinessType.TIMEOUT.getCode(), 
                        "message delayed " + delayTime + "ms, rollback redis", 
                        traceId,
                        message);
            } catch (Exception e) {
                log.warn("保存对账日志失败(延迟丢弃)", e);
            }
            return false;
        }
        return true;
    }

    /**
     * 执行实际消费逻辑：创建优惠券订单
     *
     * @param message 封装后的消息对象，包含用户 ID、优惠券 ID、订单 ID 等信息
     */
    @Override
    protected void doConsume(MessageExtend<SeckillVoucherMessage> message) {
        voucherOrderService.createVoucherOrderV2(message);
    }
    
    /**
     * 消费成功后的后置处理（异步执行）
     * 包括：
     * 1. 清理用户的订阅 ZSET 记录
     * 2. 如果需要自动发券，发送发券通知
     * 3. 统计店铺 Top 买家（按日）
     * 
     * @param message 封装后的消息对象
     */
    @Override
    protected void afterConsumeSuccess(MessageExtend<SeckillVoucherMessage> message) {
        super.afterConsumeSuccess(message);
        SeckillVoucherMessage messageBody = message.getMessageBody();
        Long userId = messageBody.getUserId();
        Long voucherId = messageBody.getVoucherId();
        Long orderId = messageBody.getOrderId();
        // 使用独立线程池异步执行后续任务，避免阻塞主流程
        SECKILL_ORDER_CONSUME_TASK_EXECUTOR.execute(() -> {
            try {
                // 构建订阅 ZSET 的 Redis Key：seckill:subscribe:{voucherId}
                RedisKeyBuild subscribeZSetKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY,
                        messageBody.getVoucherId()
                );
                // 从订阅列表中移除该用户，表示已购买成功
                redisCache.delForSortedSet(subscribeZSetKey, String.valueOf(userId));
            } catch (Exception e) {
                log.warn("清理订阅ZSET成员失败，voucherId={}, userId={}, err={}", messageBody.getVoucherId(), userId, e.getMessage());
            }
            // 判断是否需要自动发券通知
            if (Boolean.TRUE.equals(messageBody.getAutoIssue())) {
                try {
                    autoIssueNotifyService.sendAutoIssueNotify(voucherId, userId, orderId);
                } catch (Exception e) {
                    log.warn("自动发券通知发送失败，voucherId={}, userId={}, orderId={}, err={}",
                            voucherId, userId, orderId, e.getMessage());
                }
            }
            try {
                // 查询秒杀优惠券完整信息（用于获取店铺 ID）
                SeckillVoucherFullModel voucherFull = seckillVoucherService.queryByVoucherId(voucherId);
                if (Objects.isNull(voucherFull)) {
                    return;
                }
                Long shopId = voucherFull.getShopId();
                // 格式化当前日期为 yyyyMMdd
                String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
                // 构建店铺每日 Top 买家的 Redis Key：seckill:shop:topBuyers:{shopId}:{date}
                RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        shopId,
                        day
                );
                // 在有序集合中为用户分数 +1（表示购买次数 +1）
                redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(userId), 1.0);
                // 检查 Key 的过期时间
                Long ttl = redisCache.getExpire(dailyKey, TimeUnit.SECONDS);
                // 如果没有设置过期时间或已过期，设置 90 天有效期
                if (ttl == null || ttl < 0) {
                    redisCache.expire(dailyKey, 90, TimeUnit.DAYS);
                }
            } catch (Exception e) {
                log.warn("统计店铺Top买家失败，忽略不影响主流程", e);
            }
        });
    }
    
    /**
     * 消费失败后的后置处理：回滚 Redis 库存并记录对账日志
     * 
     * @param message 封装后的消息对象
     * @param throwable 抛出的异常信息
     */
    @Override
    protected void afterConsumeFailure(final MessageExtend<SeckillVoucherMessage> message, 
                                       final Throwable throwable) {
        super.afterConsumeFailure(message, throwable);
        // 默认需要回滚 Redis 库存
        SeckillVoucherOrderOperate seckillVoucherOrderOperate = SeckillVoucherOrderOperate.YES;
        // 如果是 HmdpFrameException 且错误码为"订单已存在"，则不需要回滚库存
        if (throwable instanceof HmdpFrameException hmdpFrameException) {
            if (Objects.nonNull(hmdpFrameException.getCode()) && 
                    hmdpFrameException.getCode().equals(BaseCode.VOUCHER_ORDER_EXIST.getCode())){
                seckillVoucherOrderOperate = SeckillVoucherOrderOperate.NO;
            }
        }
        // 生成全局唯一追踪 ID
        long traceId = snowflakeIdGenerator.nextId();
        redisVoucherData.rollbackRedisVoucherData(
                seckillVoucherOrderOperate,
                traceId,
                message.getMessageBody().getVoucherId(),
                message.getMessageBody().getUserId(),
                message.getMessageBody().getOrderId(),
                message.getMessageBody().getAfterQty(),
                message.getMessageBody().getChangeQty(),
                message.getMessageBody().getBeforeQty()
        );
        try {
            String detail = throwable == null ? "consume failed" : ("consume failed: " + throwable.getMessage());
            voucherReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(),
                    BusinessType.FAIL.getCode(), 
                    detail,
                    traceId,
                    message
            );
        } catch (Exception e) {
            log.warn("保存对账日志失败(消费失败)", e);
        }
    }
}
