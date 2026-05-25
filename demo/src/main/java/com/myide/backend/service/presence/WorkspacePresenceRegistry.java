package com.myide.backend.service.presence;

import com.myide.backend.dto.presence.PresenceMember;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class WorkspacePresenceRegistry {

    /*
     * workspaceId
     *   -> userId
     *      -> sessionId
     *         -> PresenceMember
     *
     * 같은 사용자가 여러 탭으로 접속할 수 있으므로 userId 아래에 sessionId를 여러 개 둡니다.
     */
    private final ConcurrentMap<String, ConcurrentMap<Long, ConcurrentMap<String, PresenceMember>>> workspaceUsers =
            new ConcurrentHashMap<>();

    /*
     * sessionId -> workspaceId, userId 역색인.
     * WebSocket disconnect 시 어떤 워크스페이스에서 나갔는지 찾기 위해 사용합니다.
     */
    private final ConcurrentMap<String, SessionIndex> sessionIndex = new ConcurrentHashMap<>();

    public void join(
            String workspaceId,
            String sessionId,
            Long userId,
            String nickname,
            String email,
            String profileImageUrl
    ) {
        if (isBlank(workspaceId) || isBlank(sessionId) || userId == null) {
            return;
        }

        Instant now = Instant.now();

        PresenceMember member = PresenceMember.builder()
                .userId(userId)
                .nickname(nickname)
                .email(email)
                .profileImageUrl(profileImageUrl)
                .sessionId(sessionId)
                .joinedAt(now)
                .lastSeenAt(now)
                .build();

        workspaceUsers
                .computeIfAbsent(workspaceId, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .put(sessionId, member);

        sessionIndex.put(sessionId, new SessionIndex(workspaceId, userId));
    }

    public void heartbeat(String workspaceId, String sessionId, Long userId) {
        if (isBlank(workspaceId) || isBlank(sessionId) || userId == null) {
            return;
        }

        ConcurrentMap<Long, ConcurrentMap<String, PresenceMember>> users =
                workspaceUsers.get(workspaceId);

        if (users == null) {
            return;
        }

        ConcurrentMap<String, PresenceMember> sessions = users.get(userId);

        if (sessions == null) {
            return;
        }

        PresenceMember member = sessions.get(sessionId);

        if (member != null) {
            member.setLastSeenAt(Instant.now());
        }
    }

    public Optional<String> leave(String sessionId) {
        if (isBlank(sessionId)) {
            return Optional.empty();
        }

        SessionIndex index = sessionIndex.remove(sessionId);

        if (index == null) {
            return Optional.empty();
        }

        ConcurrentMap<Long, ConcurrentMap<String, PresenceMember>> users =
                workspaceUsers.get(index.workspaceId());

        if (users == null) {
            return Optional.of(index.workspaceId());
        }

        ConcurrentMap<String, PresenceMember> sessions = users.get(index.userId());

        if (sessions != null) {
            sessions.remove(sessionId);

            if (sessions.isEmpty()) {
                users.remove(index.userId());
            }
        }

        if (users.isEmpty()) {
            workspaceUsers.remove(index.workspaceId());
        }

        return Optional.of(index.workspaceId());
    }

    public List<PresenceMember> getOnlineMembers(String workspaceId) {
        if (isBlank(workspaceId)) {
            return List.of();
        }

        ConcurrentMap<Long, ConcurrentMap<String, PresenceMember>> users =
                workspaceUsers.get(workspaceId);

        if (users == null) {
            return List.of();
        }

        List<PresenceMember> result = new ArrayList<>();

        for (ConcurrentMap<String, PresenceMember> sessions : users.values()) {
            sessions.values()
                    .stream()
                    .max(Comparator.comparing(PresenceMember::getLastSeenAt))
                    .ifPresent(result::add);
        }

        result.sort(
                Comparator.comparing(
                        member -> Optional.ofNullable(member.getNickname()).orElse("")
                )
        );

        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SessionIndex(String workspaceId, Long userId) {
    }
}