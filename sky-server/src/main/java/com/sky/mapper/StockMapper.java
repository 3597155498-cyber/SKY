package com.sky.mapper;

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
}