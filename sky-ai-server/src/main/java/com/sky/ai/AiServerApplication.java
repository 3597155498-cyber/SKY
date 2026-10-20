package com.sky.ai;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 推荐服务（独立进程，端口 8081）。
 * <p>
 * 只暴露 /ai/** 内部接口，由主服务通过 OpenFeign 调用；SSE 流式接口仍留在主服务，
 * 因为 Feign 是同步阻塞客户端，不支持流式返回。
 */
@SpringBootApplication
@MapperScan("com.sky.ai.mapper")
@Slf4j
public class AiServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServerApplication.class, args);
        log.info("sky-ai-server started");
    }
}
