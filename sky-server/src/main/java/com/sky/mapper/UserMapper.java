package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

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

}