package com.sky.feign;

import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Feign 超时与重试策略：
 * - 连接 1s、读取 8s：AI 服务内部还要调模型，读超时不能按普通接口的 1s 设；
 * - 不重试：推荐不是幂等写操作，重试只会把延迟翻倍，失败直接走 fallback。
 */
@Configuration
public class AiFeignConfig {

    @Bean
    public Request.Options aiRequestOptions() {
        return new Request.Options(1000, TimeUnit.MILLISECONDS,
                8000, TimeUnit.MILLISECONDS, true);
    }

    @Bean
    public Retryer aiRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
