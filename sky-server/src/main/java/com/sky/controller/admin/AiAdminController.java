package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.AiRecommendService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 AI 运维接口
 */
@RestController
@RequestMapping("/admin/ai")
@Api(tags = "管理端-AI运维")
@Slf4j
public class AiAdminController {

    @Autowired
    private AiRecommendService aiRecommendService;

    /**
     * 刷新菜品标签（离线批处理，可重跑；同一菜品会先清空再写）
     */
    @PostMapping("/tag/refresh")
    @ApiOperation("刷新菜品标签")
    public Result<Integer> refreshTags() {
        int count = aiRecommendService.refreshDishTags();
        log.info("菜品标签刷新完成, 写入 {} 条", count);
        return Result.success(count);
    }
}
