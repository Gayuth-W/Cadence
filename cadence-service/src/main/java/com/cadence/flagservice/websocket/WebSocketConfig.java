package com.cadence.flagservice.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket for live rollout monitoring. The dashboard subscribes to
 * {@code /topic/rollouts} and {@code /topic/flags/{flagId}/metrics} and sees stage transitions,
 * canary verdicts and rollbacks as they happen rather than on a poll interval.
 *
 * <p>The simple in-memory broker is correct for a single control-plane instance. Running more than one
 * replica would need an external relay (RabbitMQ/ActiveMQ), since a rollback fired on instance A would
 * otherwise never reach a dashboard connected to instance B.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final com.cadence.flagservice.security.service.JwtService jwtService;

    public WebSocketConfig(com.cadence.flagservice.security.service.JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*")
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(org.springframework.messaging.simp.config.ChannelRegistration registration) {
        registration.interceptors(new org.springframework.messaging.support.ChannelInterceptor() {
            @Override
            public org.springframework.messaging.Message<?> preSend(org.springframework.messaging.Message<?> message, org.springframework.messaging.MessageChannel channel) {
                org.springframework.messaging.simp.stomp.StompHeaderAccessor accessor =
                        org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(message, org.springframework.messaging.simp.stomp.StompHeaderAccessor.class);
                
                if (accessor != null && org.springframework.messaging.simp.stomp.StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String bearer = accessor.getFirstNativeHeader("Authorization");
                    if (bearer == null || !bearer.startsWith("Bearer ")) {
                        throw new org.springframework.messaging.MessagingException("Unauthenticated STOMP CONNECT");
                    }
                    String token = bearer.substring(7);
                    if (!jwtService.isValid(token)) {
                        throw new org.springframework.messaging.MessagingException("Invalid token on STOMP CONNECT");
                    }
                    
                    io.jsonwebtoken.Claims claims = jwtService.parse(token);
                    String username = claims.getSubject();
                    @SuppressWarnings("unchecked")
                    java.util.List<String> roles = (java.util.List<String>) claims.get("roles");
                    
                    java.util.List<org.springframework.security.core.authority.SimpleGrantedAuthority> authorities = 
                        roles.stream().map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r)).toList();
                        
                    accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(username, null, authorities));
                }
                return message;
            }
        });
    }
}
