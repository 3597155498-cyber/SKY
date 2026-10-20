package com.sky.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机：定义合法状态流转，非法流转在业务层直接拒绝
 * <p>
 * 1 待付款 → 2 待接单 / 6 已取消
 * 2 待接单 → 3 已接单 / 6 已取消（拒单或其他原因取消）
 * 3 已接单 → 4 派送中 / 6 已取消
 * 4 派送中 → 5 已完成 / 6 已取消
 * 5 已完成 → 终态
 * 6 已取消 → 终态
 */
public class OrderStatusTransfer {

    private static final Map<Integer, Set<Integer>> ALLOWED;

    static {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        map.put(1, new HashSet<>(Arrays.asList(2, 6)));
        map.put(2, new HashSet<>(Arrays.asList(3, 6)));
        map.put(3, new HashSet<>(Arrays.asList(4, 6)));
        map.put(4, new HashSet<>(Arrays.asList(5, 6)));
        map.put(5, Collections.emptySet());
        map.put(6, Collections.emptySet());
        ALLOWED = Collections.unmodifiableMap(map);
    }

    /**
     * 判断状态流转是否合法
     *
     * @param from 当前状态
     * @param to   目标状态
     */
    public static boolean canTransfer(Integer from, Integer to) {
        if (from == null || to == null) {
            return false;
        }
        Set<Integer> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }
}
