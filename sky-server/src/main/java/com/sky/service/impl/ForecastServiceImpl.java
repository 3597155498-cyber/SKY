package com.sky.service.impl;

import com.sky.mapper.OrderMapper;
import com.sky.service.ForecastService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜品销量预测
 * <p>
 * 算法：7 日移动平均 × 星期系数（用历史同日/整体均值估计），
 * 用最后 7 天做 holdout 计算 MAPE。
 * <p>
 * 为什么不上 LSTM/Prophet：单店单菜品日均只有个位数到几十条数据，
 * 时序模型的收益不足以覆盖其复杂度与运维成本，可解释性也更差。
 * 面试时这是典型的"技术选型要匹配数据量"的回答。
 */
@Service
@Slf4j
public class ForecastServiceImpl implements ForecastService {

    private static final int TRAIN_DAYS = 21;
    private static final int TEST_DAYS = 7;

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public Map<String, Object> forecast(int days, int top) {
        if (days < 14) {
            days = 28;
        }
        LocalDate today = LocalDate.now();
        LocalDate begin = today.minusDays(days - 1);
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(today, LocalTime.MAX);

        List<Map<String, Object>> rows = orderMapper.salesByDishByDay(beginTime, endTime);
        Map<String, Map<String, Double>> byDish = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name"));
            String day = String.valueOf(row.get("d"));
            double qty = row.get("qty") == null ? 0 : ((Number) row.get("qty")).doubleValue();
            Map<String, Double> series = byDish.get(name);
            if (series == null) {
                series = new HashMap<>();
                byDish.put(name, series);
            }
            series.put(day, qty);
        }

        List<LocalDate> allDays = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            allDays.add(begin.plusDays(i));
        }
        int trainSize = Math.max(7, allDays.size() - TEST_DAYS);
        LocalDate tomorrow = today.plusDays(1);

        List<Map<String, Object>> items = new ArrayList<>();
        List<Double> mapes = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : byDish.entrySet()) {
            String name = entry.getKey();
            Map<String, Double> series = entry.getValue();

            double[] values = new double[allDays.size()];
            for (int i = 0; i < allDays.size(); i++) {
                Double v = series.get(allDays.get(i).toString());
                values[i] = v == null ? 0 : v;
            }
            if (sum(values) <= 0) {
                continue;
            }

            // 训练段：整体均值 + 各星期几系数
            double trainSum = 0;
            int trainCount = 0;
            double[] weekdaySum = new double[7];
            int[] weekdayCnt = new int[7];
            for (int i = 0; i < trainSize; i++) {
                trainSum += values[i];
                trainCount++;
                int dow = allDays.get(i).getDayOfWeek().getValue() - 1;
                weekdaySum[dow] += values[i];
                weekdayCnt[dow]++;
            }
            double trainAvg = trainCount == 0 ? 0 : trainSum / trainCount;
            double[] factor = new double[7];
            for (int d = 0; d < 7; d++) {
                factor[d] = (trainAvg <= 0 || weekdayCnt[d] == 0) ? 1.0 : (weekdaySum[d] / weekdayCnt[d]) / trainAvg;
                if (factor[d] <= 0) {
                    factor[d] = 1.0;
                }
                if (factor[d] > 3) {
                    factor[d] = 3.0;
                }
            }

            // 用训练段的最后 7 天均值做基线，逐日预测 holdout
            double ma7 = meanTail(values, trainSize, 7);
            double apeSum = 0;
            int apeCnt = 0;
            for (int i = trainSize; i < values.length; i++) {
                int dow = allDays.get(i).getDayOfWeek().getValue() - 1;
                double pred = ma7 * factor[dow];
                double actual = values[i];
                if (actual > 0) {
                    apeSum += Math.abs(pred - actual) / actual;
                    apeCnt++;
                }
            }
            Double mape = apeCnt == 0 ? null : Math.round(10000.0 * apeSum / apeCnt) / 100.0;
            if (mape != null) {
                mapes.add(mape);
            }

            // 全量数据重算，预测明天
            double ma7All = meanTail(values, values.length, 7);
            int tomorrowDow = tomorrow.getDayOfWeek().getValue() - 1;
            double forecastValue = ma7All * factor[tomorrowDow];

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("ma7", Math.round(100.0 * ma7All) / 100.0);
            item.put("weekdayFactor", Math.round(100.0 * factor[tomorrowDow]) / 100.0);
            item.put("forecast", Math.round(100.0 * forecastValue) / 100.0);
            item.put("mape", mape);
            items.add(item);
        }

        items.sort((a, b) -> Double.compare(((Number) b.get("forecast")).doubleValue(),
                ((Number) a.get("forecast")).doubleValue()));

        double totalMape = 0;
        for (Double m : mapes) {
            totalMape += m;
        }
        double avgMape = mapes.isEmpty() ? 0 : Math.round(100.0 * totalMape / mapes.size()) / 100.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("begin", begin.toString());
        result.put("end", today.toString());
        result.put("targetDate", tomorrow.toString());
        result.put("dishCount", items.size());
        result.put("mape", avgMape);
        result.put("method", "7日移动平均 × 星期系数，holdout 评估");
        result.put("items", items.size() > top ? new ArrayList<>(items.subList(0, top)) : items);
        log.info("销量预测完成, 菜品数={}, MAPE={}%", items.size(), avgMape);
        return result;
    }

    private double sum(double[] values) {
        double s = 0;
        for (double v : values) {
            s += v;
        }
        return s;
    }

    /**
     * 取数组末尾 n 个元素的均值
     */
    private double meanTail(double[] values, int endExclusive, int n) {
        int start = Math.max(0, endExclusive - n);
        if (endExclusive <= start) {
            return 0;
        }
        double s = 0;
        for (int i = start; i < endExclusive; i++) {
            s += values[i];
        }
        return s / (endExclusive - start);
    }
}
