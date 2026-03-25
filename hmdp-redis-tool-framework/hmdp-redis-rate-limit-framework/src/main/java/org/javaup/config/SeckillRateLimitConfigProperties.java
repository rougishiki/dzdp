package org.javaup.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 限流配置
 * @author: 阿星不是程序员
 **/
@Data
@ConfigurationProperties(prefix = SeckillRateLimitConfigProperties.PREFIX)
public class SeckillRateLimitConfigProperties implements Serializable {
    
    public static final String PREFIX = "rate-limit";
    
    private Boolean enableSlidingWindow = false;
    
    private Integer ipWindowMillis = 5000;
    
    private Integer ipMaxAttempts = 3;
    
    private Integer userWindowMillis = 60000;
    
    private Integer userMaxAttempts = 5;
    
    private Set<String> ipWhitelist = Collections.emptySet();
    
    private Set<Long> userWhitelist = Collections.emptySet();
    
    private Boolean enablePenalty = false;
    
    private Integer violationWindowSeconds = 60;
    
    private Integer ipBlockThreshold = 5;
    
    private Integer userBlockThreshold = 5;
    
    private Integer ipBlockTtlSeconds = 300;
    
    private Integer userBlockTtlSeconds = 300;
    
    private EndpointLimit issue = new EndpointLimit();
    
    private EndpointLimit seckill = new EndpointLimit();

    @Data
    public static class EndpointLimit implements Serializable {
        private Integer ipWindowMillis;
        private Integer ipMaxAttempts;
        private Integer userWindowMillis;
        private Integer userMaxAttempts;
    }
}