package com.myide.backend.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DirectMessageEvents {
    private final SimpMessagingTemplate messages;
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void changed(DirectMessageService.Changed event) {
        // 소켓에는 본문/상대방 정보를 보내지 않습니다. 실제 데이터는 인증된 REST로 조회합니다.
        for(Long id:new Long[]{event.first(),event.second()}) {
            try { messages.convertAndSendToUser(String.valueOf(id),"/queue/dm",Map.of("type","refresh")); }
            catch(RuntimeException e) { log.warn("DM refresh signal failed for user {}",id); }
        }
    }
}