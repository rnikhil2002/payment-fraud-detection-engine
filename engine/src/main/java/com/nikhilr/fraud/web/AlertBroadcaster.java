package com.nikhilr.fraud.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikhilr.fraud.service.DecisionStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain WebSocket endpoint (/ws/alerts) that pushes alerts and a stats snapshot to every
 * connected dashboard. Messages look like {"type": "alert" | "stats", "data": {...}}.
 */
@Component
public class AlertBroadcaster extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertBroadcaster.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;
    private final DecisionStats stats;

    public AlertBroadcaster(ObjectMapper mapper, DecisionStats stats) {
        this.mapper = mapper;
        this.stats = stats;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // The decorator makes concurrent sends from Kafka listener threads safe.
        sessions.add(new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024));
        log.info("Dashboard connected ({} open)", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.removeIf(s -> s.getId().equals(session.getId()));
    }

    public void broadcast(String type, Object data) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("type", type, "data", data));
        } catch (IOException ex) {
            log.error("Could not serialize {} message", type, ex);
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException | IllegalStateException ex) {
                sessions.remove(session);
            }
        }
    }

    @Scheduled(fixedRate = 2000)
    public void pushStats() {
        broadcast("stats", stats.snapshot());
    }

    public int connectedClients() {
        return sessions.size();
    }
}
