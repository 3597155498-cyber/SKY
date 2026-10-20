package com.sky.mapper;

import com.sky.entity.CommentAnalysis;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CommentAnalysisMapper {

    /**
     * 幂等写入：同一评价重复分析只覆盖结果
     */
    @Insert("insert into comment_analysis (comment_id, sentiment, themes, analyzed_by, create_time) " +
            "values (#{commentId}, #{sentiment}, #{themes}, #{analyzedBy}, #{createTime}) " +
            "on duplicate key update sentiment = values(sentiment), themes = values(themes), analyzed_by = values(analyzed_by)")
    int upsert(CommentAnalysis analysis);

    @Select("select a.* from comment_analysis a join comment c on c.id = a.comment_id " +
            "where c.create_time between #{begin} and #{end}")
    List<CommentAnalysis> listByCommentRange(@Param("begin") LocalDateTime begin,
                                             @Param("end") LocalDateTime end);
}
