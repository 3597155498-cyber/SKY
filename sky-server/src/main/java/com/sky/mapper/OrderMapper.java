package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    //下单
    void insert(Orders orders);

    /**
     * 根据订单号查询订单
     *
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 修改订单信息
     *
     * @param orders
     */
    void update(Orders orders);

    /**
     * 条件分页查询订单
     *
     * @param ordersPageQueryDTO
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据id查询订单
     *
     * @param id
     */
    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 根据订单id和用户id查询订单
     *
     * @param id
     * @param userId
     */
    @Select("select * from orders where id = #{id} and user_id = #{userId}")
    Orders getByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 根据用户id与幂等键查询订单（幂等：重复提交时直接返回原订单）
     *
     * @param userId         用户id
     * @param idempotencyKey 幂等键
     */
    @Select("select * from orders where user_id = #{userId} and idempotency_key = #{idempotencyKey} limit 1")
    Orders getByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                        @Param("idempotencyKey") String idempotencyKey);

    /**
     * 根据状态统计订单数量
     *
     * @param status
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);

    /*
     * 根据订单状态与下单时间按查询
     * */
    @Select("select  *  from orders where  status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrderTimeLT(Integer status, LocalDateTime orderTime);

    /**
     * 超时取消订单：仅当订单仍处于待付款(1)时生效。
     * 条件更新保证"用户刚支付"与"超时取消"并发时不会互相覆盖。
     *
     * @param id           订单id
     * @param cancelReason 取消原因
     * @param cancelTime   取消时间
     * @return 影响行数，0 表示状态已变更
     */
    @Update("update orders set status = 6, cancel_reason = #{cancelReason}, cancel_time = #{cancelTime} " +
            "where id = #{id} and status = 1")
    int cancelTimeoutOrder(@Param("id") Long id,
                           @Param("cancelReason") String cancelReason,
                           @Param("cancelTime") LocalDateTime cancelTime);

    /**
     * 条件更新订单状态：只有当前状态等于 fromStatus 时才更新。
     * 并发场景（商家接单 vs 用户取消）下，只有一方能更新成功（返回 1），另一方返回 0。
     */
    int updateStatusIfMatch(@Param("id") Long id,
                            @Param("fromStatus") Integer fromStatus,
                            @Param("toStatus") Integer toStatus,
                            @Param("cancelReason") String cancelReason,
                            @Param("rejectionReason") String rejectionReason,
                            @Param("cancelTime") LocalDateTime cancelTime,
                            @Param("payStatus") Integer payStatus,
                            @Param("deliveryTime") LocalDateTime deliveryTime);

    /**
     * 按天聚合营业额（一次查询替代"每天查一次"）
     */
    @Select("select date_format(order_time, '%Y-%m-%d') as d, sum(amount) as v from orders " +
            "where status = 5 and order_time between #{begin} and #{end} group by d")
    List<Map<String, Object>> sumTurnoverByDay(@Param("begin") LocalDateTime begin,
                                               @Param("end") LocalDateTime end);

    /**
     * 按天聚合订单量，status 为空表示全部订单
     */
    @Select("<script>select date_format(order_time, '%Y-%m-%d') as d, count(id) as v from orders " +
            "where order_time between #{begin} and #{end} " +
            "<if test='status != null'> and status = #{status}</if> group by d</script>")
    List<Map<String, Object>> countOrdersByDay(@Param("begin") LocalDateTime begin,
                                               @Param("end") LocalDateTime end,
                                               @Param("status") Integer status);

    //动态统计营业额
    Double sumByMap(Map map);

    //动态统计订单数量
    Integer countByMap(Map map);


    //销量统计  指定时间内
    List<GoodsSalesDTO> getSalesTop10(LocalDateTime begin,LocalDateTime end);
}
