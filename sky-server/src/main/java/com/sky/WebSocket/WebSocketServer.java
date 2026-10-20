package com.sky.WebSocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 服务：来单提醒 / 催单
 * <p>
 * 并发要点：
 * 1、会话容器使用 ConcurrentHashMap，连接建立/断开与广播并发安全
 * 2、发送时对 session 加锁，避免多线程同时写同一连接触发 IllegalStateException
 * 3、发送失败或 onError 时移除死连接，防止内存泄漏与无效重试
 * 4、心跳使用 WebSocket ping 帧，不占用业务文本消息通道
 * <p>
 * 多实例部署时单机 sessionMap 只能覆盖本机连接，需要改用 Redis Pub/Sub
 * 或独立推送服务把消息广播到所有实例（见 README）。
 */
@Component
@Slf4j
@ServerEndpoint("/ws/{sid}")
public class WebSocketServer {

    private static final Map<String, Session> SESSION_MAP = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        SESSION_MAP.put(sid, session);
        log.info("WebSocket 建立连接, sid={}, 当前连接数={}", sid, SESSION_MAP.size());
    }

    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        log.info("收到客户端消息, sid={}, message={}", sid, message);
    }

    @OnClose
    public void onClose(@PathParam("sid") String sid) {
        SESSION_MAP.remove(sid);
        log.info("WebSocket 连接断开, sid={}, 当前连接数={}", sid, SESSION_MAP.size());
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("sid") String sid) {
        SESSION_MAP.remove(sid);
        log.warn("WebSocket 异常, 已移除连接, sid={}, 当前连接数={}, 原因={}", sid, SESSION_MAP.size(), error.getMessage());
    }

    /**
     * 群发：用于来单提醒 / 催单
     */
    public void sendToAllClient(String message) {
        SESSION_MAP.forEach((sid, session) -> send(sid, session, message));
    }

    /**
     * 定向发送
     */
    public void sendToClient(String sid, String message) {
        Session session = SESSION_MAP.get(sid);
        if (session != null) {
            send(sid, session, message);
        }
    }

    private void send(String sid, Session session, String message) {
        if (session == null || !session.isOpen()) {
            SESSION_MAP.remove(sid);
            return;
        }
        try {
            synchronized (session) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("WebSocket 发送失败, 移除连接, sid={}", sid);
            SESSION_MAP.remove(sid);
        }
    }

    /**
     * 心跳：ping 帧，客户端自动回 pong，不污染业务消息
     */
    public void pingAll() {
        SESSION_MAP.forEach((sid, session) -> {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendPing(ByteBuffer.wrap(new byte[]{1}));
                } else {
                    SESSION_MAP.remove(sid);
                }
            } catch (Exception e) {
                log.warn("WebSocket 心跳失败, 移除连接, sid={}", sid);
                SESSION_MAP.remove(sid);
            }
        });
    }

    public int getSessionCount() {
        return SESSION_MAP.size();
    }
}
