package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {

    /**
     * 根据用户id查询用户
     *
     * @param id 用户id
     * @return 用户信息
     */
    @Select("select * from user where id = #{id}")
    User getById(Long id);

    /**
     * 根据微信 openid 查询用户
     *
     * @param openid 微信唯一标识
     * @return 用户信息
     */
    @Select("select * from user where openid = #{openid} limit 1")
    User getByOpenid(String openid);

    /**
     * 新增用户并回填主键
     *
     * @param user 用户信息
     */
    @Insert("insert into user (openid, name, phone, sex, id_number, avatar, create_time) " +
            "values (#{openid}, #{name}, #{phone}, #{sex}, #{idNumber}, #{avatar}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(User user);

    Integer countByMap(Map<String, Object> map);

    /**
     * 按天聚合新增用户数（一次查询替代逐天查询）
     */
    @Select("select date_format(create_time, '%Y-%m-%d') as d, count(id) as v from user " +
            "where create_time between #{begin} and #{end} group by d")
    List<Map<String, Object>> countNewUsersByDay(@Param("begin") LocalDateTime begin,
                                                 @Param("end") LocalDateTime end);

    /**
     * 统计 begin 之前的历史用户总数，用于累计用户基线
     */
    @Select("select count(id) from user where create_time < #{begin}")
    Integer countUsersBefore(@Param("begin") LocalDateTime begin);
}
