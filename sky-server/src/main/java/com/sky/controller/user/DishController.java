package com.sky.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sky.cache.CacheClient;
import com.sky.constant.CacheKeyConstant;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.mapper.StockMapper;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Api(tags = "C端-菜品浏览接口")
public class DishController {

    @Autowired
    private DishService dishService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StockMapper stockMapper;

    /**
     * 根据分类id查询菜品（返回库存与售罄状态）
     * <p>
     * 缓存只放静态信息（名称/价格/口味/描述），库存不参与缓存：
     * 下单会频繁改库存，若库存进缓存则每成一单就要失效缓存，缓存等于没有。
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
        String key = CacheKeyConstant.DISH_LIST + categoryId;
        List<DishVO> list = cacheClient.getOrLoad(key, new TypeReference<List<DishVO>>() {
        }, () -> loadDishList(categoryId));
        fillStock(list);
        return Result.success(list);
    }

    private List<DishVO> loadDishList(Long categoryId) {
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);//查询起售中的菜品
        return dishService.listWithFlavor(dish);
    }

    /**
     * 一次批量查库存，避免逐条查库
     */
    private void fillStock(List<DishVO> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        List<Long> ids = list.stream().map(DishVO::getId).collect(Collectors.toList());
        Map<Long, Integer> stockMap = stockMapper.listDishStockByIds(ids).stream()
                .collect(Collectors.toMap(Dish::getId, d -> d.getStock() == null ? 0 : d.getStock()));
        list.forEach(vo -> {
            int stock = stockMap.getOrDefault(vo.getId(), 0);
            vo.setStock(stock);
            vo.setSoldOut(stock <= 0);
        });
    }
}
