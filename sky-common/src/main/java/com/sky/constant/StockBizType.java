package com.sky.constant;

/**
 * 库存变动业务类型，与 stock_log 唯一键配合实现回补幂等
 */
public class StockBizType {

    //下单扣减
    public static final String PLACE = "PLACE";

    //用户取消 / 商家取消
    public static final String CANCEL = "CANCEL";

    //超时未支付自动取消
    public static final String TIMEOUT = "TIMEOUT";

    //商家拒单
    public static final String REJECT = "REJECT";
}