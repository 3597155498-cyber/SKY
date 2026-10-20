package com.sky.feign;

import com.sky.dto.AiRecommendDTO;
import com.sky.result.Result;
import com.sky.vo.AiRecommendVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * AI 服务内部客户端。
 * <p>
 * 只拆同步接口：SSE 是长连接 + 流式返回，Feign 是同步阻塞客户端，不能承载 SSE，
 * 因此 /user/ai/recommend 留在主服务，由主服务异步调用这里的同步接口后再转发事件。
 */
@FeignClient(name = "sky-ai-server", path = "/ai",
        fallbackFactory = AiRemoteClientFallbackFactory.class,
        configuration = AiFeignConfig.class)
public interface AiRemoteClient {

    @PostMapping("/recommendSync")
    Result<AiRecommendVO> recommendSync(@RequestBody AiRecommendDTO dto,
                                        @RequestHeader("X-User-Id") String userId);

    @PostMapping("/tag/refresh")
    Result<Integer> refreshTags();

    @PostMapping("/comment/summary")
    Result<Map<String, Object>> commentSummary(@RequestBody Map<String, Object> payload);

    @GetMapping("/status")
    Result<Map<String, Object>> status();
}
