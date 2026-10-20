package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.entity.Comment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommentMapper {

    @Insert("insert into comment (order_id, user_id, score, content, create_time) " +
            "values (#{orderId}, #{userId}, #{score}, #{content}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Comment comment);

    @Select("select * from comment where order_id = #{orderId}")
    Comment getByOrderId(Long orderId);

    @Select("<script>select * from comment " +
            "<where><if test='score != null'> and score = #{score}</if>" +
            "<if test='begin != null'> and create_time &gt;= #{begin}</if>" +
            "<if test='end != null'> and create_time &lt;= #{end}</if></where>" +
            " order by create_time desc</script>")
    Page<Comment> pageQuery(@Param("score") Integer score,
                            @Param("begin") LocalDateTime begin,
                            @Param("end") LocalDateTime end);

    @Select("select * from comment where id not in (select comment_id from comment_analysis) order by id limit #{limit}")
    List<Comment> listUnanalyzed(@Param("limit") int limit);

    @Select("select * from comment where create_time between #{begin} and #{end}")
    List<Comment> listByRange(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    @Select("select count(*) from comment")
    Integer countAll();
}
