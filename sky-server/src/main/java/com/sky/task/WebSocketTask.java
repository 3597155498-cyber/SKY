package com.sky.task;

import com.sky.WebSocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WebSocket 心跳保活：每 30 秒发送一次 ping 帧，顺带清理已失效连接
 */
@Component
@Slf4j
public class WebSocketTask {

    @Autowired
    private WebSocketServer webSocketServer;

    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        int count = webSocketServer.getSessionCount();
        if (count > 0) {
            webSocketServer.pingAll();
            log.debug("WebSocket 心跳完成, 在线连接数={}", count);
        }
    }
}
