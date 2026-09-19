package com.myide.backend.security;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {
    public static final String AUTH_USER_ID="AUTH_USER_ID";
    private final JwtProvider jwtProvider;
    private final WebSocketSessionAuthRegistry sessionAuthRegistry;
    private AccessDeniedException denied() { return new AccessDeniedException("메시지 연결 권한이 없습니다."); }
    @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
        StompHeaderAccessor a=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
        if(a==null || a.getCommand()==null) return message;
        boolean dmOnly=a.getSessionAttributes()!=null && Boolean.TRUE.equals(a.getSessionAttributes().get("DM_ONLY"));
        if(a.getCommand()==StompCommand.CONNECT) {
            String token=token(a);
            if(!StringUtils.hasText(token)) {
                if(dmOnly) throw denied();
                return message; // 기존 팀 채팅/영상/Presence의 무토큰 연결은 이번 변경에서 유지합니다.
            }
            Long userId;
            try {
                if(!jwtProvider.validateAccessToken(token)) throw denied();
                userId=jwtProvider.getUserIdFromToken(token);
                if(userId==null) throw denied();
            } catch(RuntimeException e) { throw denied(); }
            Map<String,Object> attrs=a.getSessionAttributes();
            if(attrs==null) { attrs=new ConcurrentHashMap<>(); a.setSessionAttributes(attrs); }
            attrs.put(AUTH_USER_ID,userId);
            a.setUser(()->String.valueOf(userId));
            sessionAuthRegistry.register(a.getSessionId(),userId);
        }
        String destination=a.getDestination();
        if(a.getCommand()==StompCommand.SUBSCRIBE || a.getCommand()==StompCommand.SEND) {
            // 다른 연결에서도 내부 DM 큐를 직접 구독하거나 위조 이벤트를 발행할 수 없습니다.
            boolean mentionsDm=destination!=null && (destination.contains("/queue/dm") ||
                    (destination.startsWith("/queue/") && (destination.contains("*") || destination.contains("{"))));
            if(mentionsDm || dmOnly) {
                if(a.getCommand()!=StompCommand.SUBSCRIBE || !"/user/queue/dm".equals(destination) || a.getUser()==null)
                    throw denied();
            }
            if(a.getCommand()==StompCommand.SEND && destination!=null && destination.startsWith("/user/")) throw denied();
        }
        if(a.getCommand()==StompCommand.DISCONNECT) sessionAuthRegistry.remove(a.getSessionId());
        return message;
    }
    private String token(StompHeaderAccessor a) {
        for(String name:new String[]{"Authorization","authorization","accessToken","token"}) {
            String value=a.getFirstNativeHeader(name);
            if(StringUtils.hasText(value)) return value.startsWith("Bearer ")?value.substring(7).trim():value.trim();
        }
        return null;
    }
}