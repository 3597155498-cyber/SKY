package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.vo.DishVO;

import java.util.List;

public interface DishService {

    /*
     * 新增菜品与对应的口味数据
     * */
    public void saveWithFlavor(DishDTO dishDTO);

    //菜品分页查询
    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    List<Dish> list(Long categoryId);

    //菜品批量删除
    void deleteBatch(List<Long> ids);

    //根据id查菜以及关联的口味
    DishVO getByIdwithFlavor(Long id);

    //修改菜品
    void updateWithFlavor(DishDTO dishDTO);
}
