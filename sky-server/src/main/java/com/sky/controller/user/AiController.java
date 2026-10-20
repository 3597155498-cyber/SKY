package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.dto.AiRecommendDTO;
import com.sky.result.Result;
import com.sky.service.AiRecommendService;
import com.sky.vo.AiRecommendVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * C 端 AI 点餐助手（只推荐，不写购物车、不自动下单）
 */
@RestController
@RequestMapping("/user/ai")
@Api(tags = "C端-AI点餐助手")
@Slf4j
public class AiController {

    @Autowired
    private AiRecommendService aiRecommendService;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * 同步推荐：Knife4j 与验证脚本走这个
     */
    @PostMapping("/recommendSync")
    @ApiOperation("自然语言点餐（同步返回）")
    public Result<AiRecommendVO> recommendSync(@RequestBody AiRecommendDTO dto) {
        Long userId = BaseContext.getCurrentId();
        log.info("AI 推荐请求, userId={}, query={}", userId, dto == null ? null : dto.getQuery());
        return Result.success(aiRecommendService.recommend(dto, userId));
    }

    /**
     * 事件流式推荐：便于桌宠逐段渲染。
     * 注意：当前是"事件级"流式（parsed → items → done），不是 token 级流式。
     */
    @PostMapping(value = "/recommend", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiOperation("自然语言点餐（事件流式）")
    public SseEmitter recommend(@RequestBody AiRecommendDTO dto) {
        Long userId = BaseContext.getCurrentId();
        SseEmitter emitter = new SseEmitter(30000L);
        executor.execute(() -> {
            try {
                AiRecommendVO vo = aiRecommendService.recommend(dto, userId);
                emitter.send(SseEmitter.event().name("parsed").data(vo.getParsed() == null ? "{}" : vo.getParsed()));
                if (vo.getItems() != null) {
                    for (Object item : vo.getItems()) {
                        emitter.send(SseEmitter.event().name("item").data(item));
                    }
                }
                emitter.send(SseEmitter.event().name("done").data(vo));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 推荐失败", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * AI 状态：模型、熔断、今日调用与 token
     */
    @GetMapping("/status")
    @ApiOperation("AI 状态与今日成本")
    public Result<Map<String, Object>> status() {
        return Result.success(aiRecommendService.status());
    }
}
