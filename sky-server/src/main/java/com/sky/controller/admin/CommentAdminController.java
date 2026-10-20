package com.sky.controller.admin;

import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.CommentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 管理端：评价管理与经营洞察
 */
@RestController
@RequestMapping("/admin/comment")
@Api(tags = "管理端-评价与洞察")
@Slf4j
public class CommentAdminController {

    @Autowired
    private CommentService commentService;

    @GetMapping("/page")
    @ApiOperation("评价分页")
    public Result<PageResult> page(int page, int pageSize, Integer score) {
        return Result.success(commentService.page(page, pageSize, score));
    }

    /**
     * 离线分析：把还没分析的评价跑一遍（可重跑，幂等）
     */
    @PostMapping("/analyze")
    @ApiOperation("批量分析未处理评价")
    public Result<Integer> analyze(Integer limit) {
        int count = commentService.analyzePending(limit == null ? 200 : limit);
        return Result.success(count);
    }

    @GetMapping("/insight")
    @ApiOperation("差评洞察与建议")
    public Result<Map<String, Object>> insight(
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate begin,
            @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        return Result.success(commentService.insight(begin, end));
    }

    /**
     * 主动失效洞察缓存：导入评价、重算分析后必须调用
     */
    @PostMapping("/insight/refresh")
    @ApiOperation("刷新洞察缓存")
    public Result<Long> refreshInsight() {
        return Result.success(commentService.clearInsightCache());
    }
}
