package org.javaup;


import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.message.MessageExtend;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.Assert;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;


/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: Kafka 消息发送抽象处理器。
 * @author: 阿星不是程序员
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractProducerHandler<M extends MessageExtend<?>> {
    
    private final KafkaTemplate<String, M> kafkaTemplate;
    
    /**
     * 发送 Kafka 消息（异步）
     * 
     * @param topic 消息主题
     * @param message 消息体，需继承 MessageExtend
     * @return CompletableFuture，包含发送结果（SendResult），可通过回调处理成功或失败
     */
    public final CompletableFuture<SendResult<String, M>> sendMqMessage(String topic, M message) {
        // 参数校验：确保 topic 不为空且 message 不为 null
        Assert.hasText(topic, "topic must not be blank");
        Assert.notNull(message, "message must not be null");
        try {
            // 使用 KafkaTemplate 异步发送消息到指定 topic
            CompletableFuture<SendResult<String, M>> future = kafkaTemplate.send(topic, message);
            // 注册完成回调，无论成功或失败都会执行
            return future.whenComplete((result, throwable) -> {
                if (throwable == null) {
                    // 发送成功，调用成功回调方法记录日志
                    afterSendSuccess(result);
                } else {
                    // 发送失败，调用失败回调方法记录错误信息
                    afterSendFailure(topic, message, throwable);
                }
            });
        } catch (Exception ex) {
            // 同步异常处理：发送过程中抛出异常时记录失败日志
            afterSendFailure(topic, message, ex);
            // 创建失败的 CompletableFuture 并传递异常给调用方
            CompletableFuture<SendResult<String, M>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }
    
    @SuppressWarnings("unchecked")
    public final <T> CompletableFuture<SendResult<String, M>> sendPayload(String topic, T payload) {
        M message = (M) MessageExtend.of(payload);
        return sendMqMessage(topic, message);
    }
    
    @SuppressWarnings("unchecked")
    public final <T> CompletableFuture<SendResult<String, M>> sendPayload(String topic, String key, T payload, Map<String, String> headers) {
        M message = (M) MessageExtend.of(payload, key, headers);
        return sendRecord(topic, message);
    }
    
    public final CompletableFuture<SendResult<String, M>> sendRecord(String topic, M message) {
        Assert.hasText(topic, "topic must not be blank");
        Assert.notNull(message, "message must not be null");

        ProducerRecord<String, M> record = new ProducerRecord<>(topic, message.getKey(), message);
        Map<String, String> headers = message.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((k, v) -> {
                if (Objects.nonNull(k) && Objects.nonNull(v)) {
                    record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8)));
                }
            });
        }

        try {
            CompletableFuture<SendResult<String, M>> future = kafkaTemplate.send(record);
            return future.whenComplete((result, throwable) -> {
                if (throwable == null) {
                    afterSendSuccess(result);
                } else {
                    afterSendFailure(topic, message, throwable);
                }
            });
        } catch (Exception ex) {
            afterSendFailure(topic, message, ex);
            CompletableFuture<SendResult<String, M>> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }
    
    @SuppressWarnings("unchecked")
    public final <T> CompletableFuture<Void> sendBatch(String topic, List<T> payloads) {
        Assert.hasText(topic, "topic must not be blank");
        Assert.notNull(payloads, "payloads must not be null");
        CompletableFuture<?>[] futures = payloads.stream()
                .map(p -> (M) MessageExtend.of(p))
                .map(m -> sendMqMessage(topic, m))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }
    
    public final <T> SendResult<String, M> sendAndWait(String topic, T payload) throws ExecutionException, InterruptedException {
        return sendPayload(topic, payload).get();
    }
    
    @SuppressWarnings("unchecked")
    public final <T> CompletableFuture<SendResult<String, M>> sendToDlq(String originalTopic, T payload, String reason) {
        String dlqTopic = originalTopic + ".DLQ";
        M message = (M) MessageExtend.of(payload);
        message.setHeaders(Map.of("dlqReason", reason));
        return sendRecord(dlqTopic, message);
    }
    
    protected void afterSendSuccess(SendResult<String, M> result) {
        log.info("kafka message send success, topic={}, partition={}, offset={}",
            result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
            result.getRecordMetadata().offset());
    }
    
    protected void afterSendFailure(String topic, M message, Throwable throwable) {
        log.error("kafka message send failed, topic={}, message={}", topic, JSON.toJSON(message), throwable);
    }
}
