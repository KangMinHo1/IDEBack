package com.myide.backend.service.webrtc;

import com.myide.backend.dto.webrtc.VoiceParticipant;
import com.myide.backend.dto.webrtc.VoiceRoomMember;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class VoiceRoomRegistry {

    public static final String DEFAULT_CHANNEL_ID = "general";

    private final ConcurrentMap<String, WorkspaceVoiceState> workspaces = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, SessionRef> sessionRefs = new ConcurrentHashMap<>();

    public JoinResult join(
            String workspaceId,
            String channelId,
            Long userId,
            String nickname,
            String sessionId
    ) {
        if (workspaceId == null || userId == null || sessionId == null) {
            throw new IllegalArgumentException("workspaceId, userId, sessionId are required.");
        }

        String safeChannelId = normalizeChannelId(channelId);
        WorkspaceVoiceState workspaceState = getWorkspaceState(workspaceId);

        List<LeaveEvent> previousLeaveEvents = new ArrayList<>();

        SessionRef previousSessionRef = sessionRefs.get(sessionId);

        if (previousSessionRef != null) {
            internalLeave(
                    previousSessionRef.getWorkspaceId(),
                    previousSessionRef.getChannelId(),
                    previousSessionRef.getUserId(),
                    null,
                    true
            ).ifPresent(previousLeaveEvents::add);
        }

        previousLeaveEvents.addAll(removeUserFromAllChannels(workspaceId, userId, sessionId));

        ConcurrentMap<Long, VoiceRoomMember> room =
                workspaceState.getRooms()
                        .computeIfAbsent(safeChannelId, key -> new ConcurrentHashMap<>());

        VoiceRoomMember member = VoiceRoomMember.builder()
                .userId(userId)
                .nickname(nickname)
                .workspaceId(workspaceId)
                .channelId(safeChannelId)
                .sessionId(sessionId)
                .muted(false)
                .joinedAt(Instant.now())
                .build();

        room.put(userId, member);
        sessionRefs.put(sessionId, new SessionRef(workspaceId, safeChannelId, userId));

        return new JoinResult(
                getParticipants(workspaceId, safeChannelId),
                previousLeaveEvents
        );
    }

    public Optional<LeaveEvent> leave(
            String workspaceId,
            String channelId,
            Long userId,
            String sessionId
    ) {
        String safeChannelId = normalizeChannelId(channelId);
        return internalLeave(workspaceId, safeChannelId, userId, sessionId, true);
    }

    public Optional<LeaveEvent> leaveBySession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }

        SessionRef ref = sessionRefs.remove(sessionId);

        if (ref == null) {
            return Optional.empty();
        }

        return internalLeave(
                ref.getWorkspaceId(),
                ref.getChannelId(),
                ref.getUserId(),
                null,
                false
        );
    }

    public boolean isParticipant(String workspaceId, String channelId, Long userId) {
        if (workspaceId == null || userId == null) {
            return false;
        }

        String safeChannelId = normalizeChannelId(channelId);
        WorkspaceVoiceState workspaceState = workspaces.get(workspaceId);

        if (workspaceState == null) {
            return false;
        }

        ConcurrentMap<Long, VoiceRoomMember> room = workspaceState.getRooms().get(safeChannelId);

        return room != null && room.containsKey(userId);
    }

    public List<VoiceParticipant> getParticipants(String workspaceId, String channelId) {
        String safeChannelId = normalizeChannelId(channelId);
        WorkspaceVoiceState workspaceState = workspaces.get(workspaceId);

        if (workspaceState == null) {
            return List.of();
        }

        ConcurrentMap<Long, VoiceRoomMember> room = workspaceState.getRooms().get(safeChannelId);

        if (room == null) {
            return List.of();
        }

        List<VoiceParticipant> participants = new ArrayList<>();

        for (VoiceRoomMember member : room.values()) {
            participants.add(toParticipant(member));
        }

        participants.sort(Comparator.comparing(VoiceParticipant::getJoinedAt));

        return participants;
    }

    public Optional<VoiceParticipant> updateMuted(
            String workspaceId,
            String channelId,
            Long userId,
            boolean muted
    ) {
        String safeChannelId = normalizeChannelId(channelId);
        WorkspaceVoiceState workspaceState = workspaces.get(workspaceId);

        if (workspaceState == null) {
            return Optional.empty();
        }

        ConcurrentMap<Long, VoiceRoomMember> room = workspaceState.getRooms().get(safeChannelId);

        if (room == null) {
            return Optional.empty();
        }

        VoiceRoomMember member = room.get(userId);

        if (member == null) {
            return Optional.empty();
        }

        member.setMuted(muted);

        return Optional.of(toParticipant(member));
    }

    /*
     * 채널 삭제 시 해당 채널에 있던 현재 참여자들을 메모리에서 제거합니다.
     * 채널 목록 삭제는 DB에서 VoiceChannelService가 담당합니다.
     */
    public List<VoiceParticipant> removeChannelParticipants(
            String workspaceId,
            String channelId
    ) {
        String safeChannelId = normalizeChannelId(channelId);
        WorkspaceVoiceState workspaceState = workspaces.get(workspaceId);

        if (workspaceState == null) {
            return List.of();
        }

        ConcurrentMap<Long, VoiceRoomMember> removedRoom =
                workspaceState.getRooms().remove(safeChannelId);

        if (removedRoom == null || removedRoom.isEmpty()) {
            return List.of();
        }

        List<VoiceParticipant> removedParticipants = new ArrayList<>();

        for (VoiceRoomMember member : removedRoom.values()) {
            if (member.getSessionId() != null) {
                sessionRefs.remove(member.getSessionId());
            }

            removedParticipants.add(toParticipant(member));
        }

        removedRoom.clear();

        removedParticipants.sort(Comparator.comparing(VoiceParticipant::getJoinedAt));

        return removedParticipants;
    }

    private Optional<LeaveEvent> internalLeave(
            String workspaceId,
            String channelId,
            Long userId,
            String sessionId,
            boolean removeSessionRef
    ) {
        if (workspaceId == null || userId == null) {
            return Optional.empty();
        }

        String safeChannelId = normalizeChannelId(channelId);
        WorkspaceVoiceState workspaceState = workspaces.get(workspaceId);

        if (workspaceState == null) {
            return Optional.empty();
        }

        ConcurrentMap<Long, VoiceRoomMember> room = workspaceState.getRooms().get(safeChannelId);

        if (room == null) {
            return Optional.empty();
        }

        VoiceRoomMember member = room.get(userId);

        if (member == null) {
            return Optional.empty();
        }

        if (
                sessionId != null
                        && member.getSessionId() != null
                        && !sessionId.equals(member.getSessionId())
        ) {
            sessionRefs.remove(sessionId);
            return Optional.empty();
        }

        room.remove(userId);

        if (removeSessionRef && member.getSessionId() != null) {
            sessionRefs.remove(member.getSessionId());
        }

        if (room.isEmpty()) {
            workspaceState.getRooms().remove(safeChannelId);
        }

        return Optional.of(
                new LeaveEvent(
                        workspaceId,
                        safeChannelId,
                        toParticipant(member)
                )
        );
    }

    private List<LeaveEvent> removeUserFromAllChannels(
            String workspaceId,
            Long userId,
            String currentSessionId
    ) {
        WorkspaceVoiceState workspaceState = workspaces.get(workspaceId);

        if (workspaceState == null) {
            return List.of();
        }

        List<LeaveEvent> leaveEvents = new ArrayList<>();

        for (String channelId : new ArrayList<>(workspaceState.getRooms().keySet())) {
            ConcurrentMap<Long, VoiceRoomMember> room = workspaceState.getRooms().get(channelId);

            if (room == null) {
                continue;
            }

            VoiceRoomMember member = room.get(userId);

            if (member == null) {
                continue;
            }

            if (currentSessionId != null && currentSessionId.equals(member.getSessionId())) {
                continue;
            }

            internalLeave(workspaceId, channelId, userId, null, true)
                    .ifPresent(leaveEvents::add);
        }

        return leaveEvents;
    }

    private WorkspaceVoiceState getWorkspaceState(String workspaceId) {
        return workspaces.computeIfAbsent(workspaceId, key -> new WorkspaceVoiceState());
    }

    private VoiceParticipant toParticipant(VoiceRoomMember member) {
        return VoiceParticipant.builder()
                .userId(member.getUserId())
                .nickname(member.getNickname())
                .channelId(member.getChannelId())
                .muted(member.isMuted())
                .joinedAt(member.getJoinedAt())
                .build();
    }

    private String normalizeChannelId(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return DEFAULT_CHANNEL_ID;
        }

        return channelId.trim();
    }

    @Getter
    private static class WorkspaceVoiceState {
        private final ConcurrentMap<String, ConcurrentMap<Long, VoiceRoomMember>> rooms =
                new ConcurrentHashMap<>();
    }

    @Getter
    @AllArgsConstructor
    private static class SessionRef {
        private String workspaceId;
        private String channelId;
        private Long userId;
    }

    @Getter
    @AllArgsConstructor
    public static class JoinResult {
        private List<VoiceParticipant> participants;
        private List<LeaveEvent> previousLeaveEvents;
    }

    @Getter
    @AllArgsConstructor
    public static class LeaveEvent {
        private String workspaceId;
        private String channelId;
        private VoiceParticipant participant;
    }
}