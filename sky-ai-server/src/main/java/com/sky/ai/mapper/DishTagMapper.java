package com.sky.ai.mapper;

import com.sky.entity.DishTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DishTagMapper {

    /**
     * 幂等写入：同一菜品同一标签类型同一值不重复
     */
    @Insert("insert into dish_tag (dish_id, tag_type, tag_value, create_time) " +
            "values (#{dishId}, #{tagType}, #{tagValue}, #{createTime}) " +
            "on duplicate key update tag_value = values(tag_value)")
    int upsert(DishTag dishTag);

    @Select("select * from dish_tag")
    List<DishTag> listAll();

    @Delete("delete from dish_tag where dish_id = #{dishId}")
    int deleteByDishId(Long dishId);
}
