package com.sky.controller.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sky.cache.CacheClient;
import com.sky.constant.CacheKeyConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealStockDTO;
import com.sky.entity.Setmeal;
import com.sky.mapper.StockMapper;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
@Api(tags = "C端-套餐浏览接口")
@Slf4j
public class SetmealController {

    @Autowired
    private SetmealService setmealService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StockMapper stockMapper;

    /**
     * 条件查询（返回可售份数与售罄状态）
     * <p>
     * 缓存只放套餐静态信息；可售量依赖菜品实时库存，读取时按套餐批量聚合计算。
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询套餐")
    public Result<List<SetmealVO>> list(Long categoryId) {
        String key = CacheKeyConstant.SETMEAL_LIST + categoryId;
        List<SetmealVO> list = cacheClient.getOrLoad(key, new TypeReference<List<SetmealVO>>() {
        }, () -> loadSetmealList(categoryId));
        fillAvailability(list);
        return Result.success(list);
    }

    private List<SetmealVO> loadSetmealList(Long categoryId) {
        Setmeal setmeal = new Setmeal();
        setmeal.setCategoryId(categoryId);
        setmeal.setStatus(StatusConstant.ENABLE);

        List<Setmeal> setmeals = setmealService.list(setmeal);
        List<SetmealVO> result = new ArrayList<>();
        for (Setmeal item : setmeals) {
            SetmealVO vo = new SetmealVO();
            BeanUtils.copyProperties(item, vo);
            result.add(vo);
        }
        return result;
    }

    /**
     * 一次聚合查询算出所有套餐的可支撑份数，避免逐个套餐查库
     */
    private void fillAvailability(List<SetmealVO> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        List<Long> ids = list.stream().map(SetmealVO::getId).collect(Collectors.toList());
        Map<Long, SetmealStockDTO> availability = stockMapper.listSetmealAvailability(ids).stream()
                .collect(Collectors.toMap(SetmealStockDTO::getId, dto -> dto));
        list.forEach(vo -> {
            SetmealStockDTO dto = availability.get(vo.getId());
            int selfStock = (dto == null || dto.getStock() == null) ? 0 : dto.getStock();
            int componentStock = (dto == null || dto.getComponentStock() == null) ? 0 : dto.getComponentStock();
            int available = Math.min(selfStock, componentStock);
            vo.setStock(available);
            vo.setSoldOut(available <= 0);
        });
    }

    /**
     * 根据套餐id查询包含的菜品列表
     *
     * @param id
     * @return
     */
    @GetMapping("/dish/{id}")
    @ApiOperation("根据套餐id查询包含的菜品列表")
    public Result<List<DishItemVO>> dishList(@PathVariable("id") Long id) {
        List<DishItemVO> list = setmealService.getDishItemById(id);
        return Result.success(list);
    }
}
