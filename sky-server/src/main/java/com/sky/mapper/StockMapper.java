package com.sky.mapper;

import com.sky.dto.SetmealStockDTO;
import com.sky.entity.Dish;
import com.sky.entity.StockLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 库存相关：扣减一律使用 stock >= ? 的原子条件更新，保证并发下不超卖
 */
@Mapper
public interface StockMapper {

    @Update("update dish set stock = stock - #{qty} where id = #{id} and stock >= #{qty}")
    int deductDishStock(@Param("id") Long id, @Param("qty") int qty);

    @Update("update setmeal set stock = stock - #{qty} where id = #{id} and stock >= #{qty}")
    int deductSetmealStock(@Param("id") Long id, @Param("qty") int qty);

    @Update("update dish set stock = stock + #{qty} where id = #{id}")
    int restoreDishStock(@Param("id") Long id, @Param("qty") int qty);

    @Update("update setmeal set stock = stock + #{qty} where id = #{id}")
    int restoreSetmealStock(@Param("id") Long id, @Param("qty") int qty);

    @Insert("insert into stock_log (order_id, order_detail_id, target_type, target_id, change_qty, biz_type, create_time) " +
            "values (#{orderId}, #{orderDetailId}, #{targetType}, #{targetId}, #{changeQty}, #{bizType}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StockLog stockLog);

    @Select("select * from stock_log where order_id = #{orderId} and biz_type = #{bizType}")
    List<StockLog> listByOrderAndBiz(@Param("orderId") Long orderId, @Param("bizType") String bizType);

    @Select("select stock from dish where id = #{id}")
    Integer getDishStock(@Param("id") Long id);

    @Select("select stock from setmeal where id = #{id}")
    Integer getSetmealStock(@Param("id") Long id);

    /**
     * 批量查询菜品库存（列表接口一次查完，避免 N+1）
     */
    @Select("<script>select id, stock from dish where id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Dish> listDishStockByIds(@Param("ids") List<Long> ids);

    /**
     * 批量计算套餐可售量：
     * 套餐自身库存 与 组成菜品可支撑份数（dish.stock / copies 取整）的较小值
     */
    @Select("<script>select s.id as id, s.stock as stock, " +
            "min(floor(d.stock / greatest(ifnull(sd.copies,1),1))) as componentStock " +
            "from setmeal s " +
            "left join setmeal_dish sd on sd.setmeal_id = s.id " +
            "left join dish d on d.id = sd.dish_id " +
            "where s.id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "group by s.id, s.stock</script>")
    List<SetmealStockDTO> listSetmealAvailability(@Param("ids") List<Long> ids);
}
