package org.javaup.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.RollbackFailureLog;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IRollbackAlertService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 回滚失败通知服务：用于发送短信/邮件告警（可插拔实现）。
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class RollbackAlertServiceImpl implements IRollbackAlertService {

    @Value("${seckill.rollback.alert.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${seckill.rollback.alert.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${seckill.rollback.alert.sms.to:}")
    private String smsTo;

    @Value("${seckill.rollback.alert.email.to:}")
    private String emailTo;

    @Value("${seckill.rollback.alert.dedup.window.seconds:300}")
    private long dedupWindowSeconds;

    @Resource
    private RedisCache redisCache;

    @Override
    public void sendRollbackAlert(RollbackFailureLog logEntity) {
        try {
            if (!shouldNotify(logEntity.getVoucherId())) {
                return;
            }
            String content = formatContent(logEntity);
            if (smsEnabled && smsTo != null && !smsTo.isEmpty()) {
                log.warn("[ROLLBACK_SMS] to={} content={} ", smsTo, content);
            }
            if (emailEnabled && emailTo != null && !emailTo.isEmpty()) {
                log.warn("[ROLLBACK_EMAIL] to={} content={} ", emailTo, content);
            }
        } catch (Exception e) {
            log.warn("发送回滚失败通知异常", e);
        }
    }

    private boolean shouldNotify(Long voucherId) {
        try {
            return redisCache.setIfAbsent(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_ROLLBACK_ALERT_DEDUP_KEY,voucherId),
                    "1", 
                    dedupWindowSeconds, 
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            return true;
        }
    }

    private String formatContent(RollbackFailureLog rollbackFailureLog) {
        String time = 
                rollbackFailureLog.getCreateTime() == null ? 
                        "" 
                        : 
                        rollbackFailureLog.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return String.format("回滚失败告警 | voucherId=%s userId=%s orderId=%s traceId=%s attempts=%s source=%s time=%s detail=%s", 
                rollbackFailureLog.getVoucherId(), 
                rollbackFailureLog.getUserId(), 
                rollbackFailureLog.getOrderId(), 
                rollbackFailureLog.getTraceId(), 
                rollbackFailureLog.getRetryAttempts(), 
                rollbackFailureLog.getSource(),
                time,
                rollbackFailureLog.getDetail());
    }
}