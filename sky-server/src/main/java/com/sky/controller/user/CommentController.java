package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.dto.CommentDTO;
import com.sky.result.Result;
import com.sky.service.CommentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端：订单评价
 */
@RestController
@RequestMapping("/user/comment")
@Api(tags = "C端-订单评价")
@Slf4j
public class CommentController {

    @Autowired
    private CommentService commentService;

    @PostMapping
    @ApiOperation("提交订单评价")
    public Result submit(@RequestBody CommentDTO commentDTO) {
        commentService.submit(commentDTO, BaseContext.getCurrentId());
        return Result.success();
    }
}
