package com.sky.service;

import com.sky.constant.AiConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.AiRecommendDTO;
import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Dish;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderMapper;
import com.sky.vo.AiItemVO;
import com.sky.vo.AiRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 服务不可用时的本地降级：只查热销菜，不调用模型，保证前端仍有可点菜品。
 */
@Service
@Slf4j
public class AiFallbackService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Value("${sky.ai.max-items:5}")
    private int maxItems;

    public AiRecommendVO buildHotRecommend(AiRecommendDTO dto) {
        Long categoryId = dto == null ? null : dto.getCategoryId();
        List<AiItemVO> items = new ArrayList<>();
        try {
            Dish query = new Dish();
            query.setStatus(StatusConstant.ENABLE);
            if (categoryId != null) {
                query.setCategoryId(categoryId);
            }
            List<Dish> dishes = dishMapper.list(query);
            if (dishes != null) {
                Map<String, Integer> rank = loadSalesRank();
                List<Dish> sorted = new ArrayList<>(dishes);
                sorted.sort(Comparator.comparingInt((Dish dish) -> rank.getOrDefault(dish.getName(), 999)));
                for (Dish dish : sorted) {
                    if (items.size() >= maxItems) {
                        break;
                    }
                    if (dish.getStock() != null && dish.getStock() <= 0) {
                        continue;
                    }
                    items.add(AiItemVO.builder()
                            .dishId(dish.getId())
                            .name(dish.getName())
                            .price(dish.getPrice())
                            .image(dish.getImage())
                            .score(0)
                            .reason("本店热销")
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("AI 降级推荐查询失败: {}", e.getMessage());
        }
        return AiRecommendVO.builder()
                .mode(AiConstant.MODE_DEGRADED)
                .degraded(true)
                .needConfirm(true)
                .emotion(AiConstant.EMOTION_CONFUSED)
                .speech("AI 服务暂时不可用，先给你几个本店热销的～")
                .items(items)
                .build();
    }

    public Map<String, Object> buildUnavailableStatus() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", false);
        map.put("circuitOpen", true);
        map.put("model", "unavailable");
        map.put("today", new HashMap<String, Object>());
        return map;
    }

    private Map<String, Integer> loadSalesRank() {
        Map<String, Integer> rank = new HashMap<>();
        try {
            LocalDateTime begin = LocalDateTime.now().minusDays(30);
            List<GoodsSalesDTO> top = orderMapper.getSalesTop10(begin, LocalDateTime.now());
            if (top != null) {
                for (int i = 0; i < top.size(); i++) {
                    rank.put(top.get(i).getName(), i + 1);
                }
            }
        } catch (Exception e) {
            log.warn("AI 降级推荐销量统计失败, 忽略销量分: {}", e.getMessage());
        }
        return rank;
    }
}
