package com.sky.ai.mapper;

import com.sky.entity.Dish;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * AI 服务只读查询菜品：不依赖主服务的 Mapper，保持服务边界独立。
 */
@Mapper
public interface AiDishMapper {

    /**
     * 按状态/分类查询菜品（字段与 sky-server 的 DishMapper.list 保持一致）
     */
    List<Dish> list(Dish dish);
}
