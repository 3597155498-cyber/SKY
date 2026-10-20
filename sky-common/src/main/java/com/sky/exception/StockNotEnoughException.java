package com.sky.exception;

/**
 * 库存不足异常
 */
public class StockNotEnoughException extends BaseException {

    public StockNotEnoughException() {
    }

    public StockNotEnoughException(String msg) {
        super(msg);
    }
}