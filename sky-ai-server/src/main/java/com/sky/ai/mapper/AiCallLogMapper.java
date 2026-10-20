package com.sky.ai.mapper;

import com.sky.entity.AiCallLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 调用日志：AI 服务自己写，主服务不再直接写 ai_call_log。
 */
@Mapper
public interface AiCallLogMapper {

    @Insert("insert into ai_call_log (biz_type, model, prompt_tokens, completion_tokens, cost_ms, degraded, success, error_msg, trace_id, create_time) " +
            "values (#{bizType}, #{model}, #{promptTokens}, #{completionTokens}, #{costMs}, #{degraded}, #{success}, #{errorMsg}, #{traceId}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiCallLog aiCallLog);

    /**
     * 今日统计：调用次数、token 总量、降级次数、平均耗时，供 /ai/status 展示
     */
    @Select("select count(*) as calls, " +
            "ifnull(sum(prompt_tokens),0) + ifnull(sum(completion_tokens),0) as tokens, " +
            "ifnull(sum(degraded),0) as degraded, " +
            "ifnull(avg(cost_ms),0) as avgCostMs " +
            "from ai_call_log where create_time >= #{begin}")
    Map<String, Object> statisticsSince(@Param("begin") LocalDateTime begin);
}
