package com.cadence.flagservice.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Pushes rollout lifecycle events to any connected dashboard.
 *
 * <p>Every publish is wrapped: a dead WebSocket must never be able to abort an automatic rollback.
 * Notifying the humans is strictly less important than withdrawing the bad candidate.
 */
@Component
public class RolloutBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RolloutBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public RolloutBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void flagChanged(UUID flagId, String flagKey, String event, Map<String, Object> payload) {
        send("/topic/rollouts", Map.of(
                "flagId", flagId.toString(),
                "flagKey", flagKey,
                "event", event,
                "payload", payload,
                "timestamp", Instant.now().toString()));
    }

    public void metrics(UUID flagId, Object snapshot) {
        send("/topic/flags/" + flagId + "/metrics", snapshot);
    }

    private void send(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.debug("WebSocket broadcast to {} failed: {}", destination, e.getMessage());
        }
    }
}
