package com.sky.exception;

/**
 * 大模型不可用（超时、限流、熔断），由调用方降级处理
 */
public class AiUnavailableException extends BaseException {

    public AiUnavailableException() {
    }

    public AiUnavailableException(String msg) {
        super(msg);
    }
}
