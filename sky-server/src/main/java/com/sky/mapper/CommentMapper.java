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

    /**
     * 按天聚合评价趋势：总数与差评数，供看板画曲线
     */
    @Select("select date_format(c.create_time, '%Y-%m-%d') as d, count(*) as total, " +
            "ifnull(sum(case when a.sentiment = 'NEGATIVE' then 1 else 0 end), 0) as negative " +
            "from comment c left join comment_analysis a on a.comment_id = c.id " +
            "where c.create_time between #{begin} and #{end} group by d order by d")
    java.util.List<java.util.Map<String, Object>> dailyTrend(@Param("begin") LocalDateTime begin,
                                                             @Param("end") LocalDateTime end);
}
