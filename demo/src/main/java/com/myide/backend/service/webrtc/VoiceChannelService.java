package com.myide.backend.service.webrtc;

import com.myide.backend.domain.webrtc.VoiceChannelEntity;
import com.myide.backend.dto.webrtc.VoiceChannel;

import com.myide.backend.repository.webrtc.VoiceChannelRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class VoiceChannelService {

    public static final String DEFAULT_CHANNEL_ID = "general";
    public static final String DEFAULT_CHANNEL_NAME = "일반 회의실";
    public static final String DEFAULT_CHANNEL_ICON = "💬";

    private static final int MAX_CHANNEL_NAME_LENGTH = 30;
    private static final int MAX_CHANNEL_ICON_CODE_POINTS = 4;

    private final VoiceChannelRepository voiceChannelRepository;

    public List<VoiceChannel> getChannels(String workspaceId) {
        String safeWorkspaceId = normalizeWorkspaceId(workspaceId);

        ensureDefaultChannel(safeWorkspaceId);

        return voiceChannelRepository
                .findByWorkspaceIdAndDeletedFalseOrderBySortOrderAscCreatedAtAsc(safeWorkspaceId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public boolean existsChannel(String workspaceId, String channelId) {
        String safeWorkspaceId = normalizeWorkspaceId(workspaceId);
        String safeChannelId = normalizeChannelId(channelId);

        ensureDefaultChannel(safeWorkspaceId);

        return voiceChannelRepository.existsByWorkspaceIdAndChannelIdAndDeletedFalse(
                safeWorkspaceId,
                safeChannelId
        );
    }

    public VoiceChannel createChannel(
            String workspaceId,
            String channelName,
            String channelIcon,
            Long createdBy
    ) {
        String safeWorkspaceId = normalizeWorkspaceId(workspaceId);

        ensureDefaultChannel(safeWorkspaceId);

        String safeName = sanitizeChannelName(channelName, "새 음성 채널");
        String safeIcon = sanitizeChannelIcon(channelIcon, DEFAULT_CHANNEL_ICON);
        String channelId = generateUniqueChannelId(safeWorkspaceId);
        int nextSortOrder = getNextSortOrder(safeWorkspaceId);

        VoiceChannelEntity entity = VoiceChannelEntity.builder()
                .workspaceId(safeWorkspaceId)
                .channelId(channelId)
                .name(safeName)
                .icon(safeIcon)
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .deleted(false)
                .deletedAt(null)
                .sortOrder(nextSortOrder)
                .build();

        VoiceChannelEntity saved = voiceChannelRepository.save(entity);

        return toDto(saved);
    }

    public Optional<UpdateChannelResult> updateChannel(
            String workspaceId,
            String channelId,
            String nextChannelName,
            String nextChannelIcon
    ) {
        String safeWorkspaceId = normalizeWorkspaceId(workspaceId);
        String safeChannelId = normalizeChannelId(channelId);

        ensureDefaultChannel(safeWorkspaceId);

        Optional<VoiceChannelEntity> optionalEntity =
                voiceChannelRepository.findByWorkspaceIdAndChannelIdAndDeletedFalse(
                        safeWorkspaceId,
                        safeChannelId
                );

        if (optionalEntity.isEmpty()) {
            return Optional.empty();
        }

        VoiceChannelEntity entity = optionalEntity.get();

        String safeName = sanitizeChannelName(nextChannelName, entity.getName());
        String safeIcon = sanitizeChannelIcon(nextChannelIcon, entity.getIcon());

        entity.setName(safeName);
        entity.setIcon(safeIcon);

        VoiceChannelEntity saved = voiceChannelRepository.save(entity);

        List<VoiceChannel> channels = getChannels(safeWorkspaceId);

        return Optional.of(
                new UpdateChannelResult(
                        toDto(saved),
                        channels
                )
        );
    }

    public Optional<DeleteChannelResult> deleteChannel(String workspaceId, String channelId) {
        String safeWorkspaceId = normalizeWorkspaceId(workspaceId);
        String safeChannelId = normalizeChannelId(channelId);

        ensureDefaultChannel(safeWorkspaceId);

        if (DEFAULT_CHANNEL_ID.equals(safeChannelId)) {
            return Optional.empty();
        }

        Optional<VoiceChannelEntity> optionalEntity =
                voiceChannelRepository.findByWorkspaceIdAndChannelIdAndDeletedFalse(
                        safeWorkspaceId,
                        safeChannelId
                );

        if (optionalEntity.isEmpty()) {
            return Optional.empty();
        }

        VoiceChannelEntity entity = optionalEntity.get();

        entity.setDeleted(true);
        entity.setDeletedAt(Instant.now());

        VoiceChannelEntity saved = voiceChannelRepository.save(entity);

        List<VoiceChannel> remainingChannels = voiceChannelRepository
                .findByWorkspaceIdAndDeletedFalseOrderBySortOrderAscCreatedAtAsc(safeWorkspaceId)
                .stream()
                .map(this::toDto)
                .toList();

        return Optional.of(
                new DeleteChannelResult(
                        toDto(saved),
                        remainingChannels
                )
        );
    }

    public VoiceChannel ensureDefaultChannel(String workspaceId) {
        String safeWorkspaceId = normalizeWorkspaceId(workspaceId);

        Optional<VoiceChannelEntity> optionalExisting =
                voiceChannelRepository.findByWorkspaceIdAndChannelId(
                        safeWorkspaceId,
                        DEFAULT_CHANNEL_ID
                );

        if (optionalExisting.isPresent()) {
            VoiceChannelEntity existing = optionalExisting.get();

            if (existing.isDeleted()) {
                existing.setDeleted(false);
                existing.setDeletedAt(null);
                existing.setName(DEFAULT_CHANNEL_NAME);
                existing.setIcon(DEFAULT_CHANNEL_ICON);
                existing.setSortOrder(0);

                return toDto(voiceChannelRepository.save(existing));
            }

            return toDto(existing);
        }

        VoiceChannelEntity entity = VoiceChannelEntity.builder()
                .workspaceId(safeWorkspaceId)
                .channelId(DEFAULT_CHANNEL_ID)
                .name(DEFAULT_CHANNEL_NAME)
                .icon(DEFAULT_CHANNEL_ICON)
                .createdBy(null)
                .createdAt(Instant.EPOCH)
                .deleted(false)
                .deletedAt(null)
                .sortOrder(0)
                .build();

        VoiceChannelEntity saved = voiceChannelRepository.save(entity);

        return toDto(saved);
    }

    private int getNextSortOrder(String workspaceId) {
        Integer maxSortOrder = voiceChannelRepository.findMaxSortOrderByWorkspaceId(workspaceId);

        if (maxSortOrder == null) {
            return 1;
        }

        return maxSortOrder + 1;
    }

    private VoiceChannel toDto(VoiceChannelEntity entity) {
        return VoiceChannel.builder()
                .channelId(entity.getChannelId())
                .name(entity.getName())
                .icon(entity.getIcon())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .sortOrder(entity.getSortOrder())
                .defaultChannel(DEFAULT_CHANNEL_ID.equals(entity.getChannelId()))
                .build();
    }

    private String normalizeWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId is required.");
        }

        return workspaceId.trim();
    }

    private String normalizeChannelId(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return DEFAULT_CHANNEL_ID;
        }

        return channelId.trim();
    }

    private String sanitizeChannelName(String channelName, String fallback) {
        if (channelName == null || channelName.isBlank()) {
            return fallback;
        }

        String trimmed = channelName.trim();

        if (trimmed.length() > MAX_CHANNEL_NAME_LENGTH) {
            return trimmed.substring(0, MAX_CHANNEL_NAME_LENGTH);
        }

        return trimmed;
    }

    private String sanitizeChannelIcon(String channelIcon, String fallback) {
        if (channelIcon == null || channelIcon.isBlank()) {
            return fallback;
        }

        String trimmed = channelIcon.trim();

        int codePointCount = trimmed.codePointCount(0, trimmed.length());

        if (codePointCount <= MAX_CHANNEL_ICON_CODE_POINTS) {
            return trimmed;
        }

        int endIndex = trimmed.offsetByCodePoints(0, MAX_CHANNEL_ICON_CODE_POINTS);

        return trimmed.substring(0, endIndex);
    }

    private String generateUniqueChannelId(String workspaceId) {
        for (int i = 0; i < 10; i++) {
            String channelId = "voice-" + UUID.randomUUID().toString().substring(0, 8);

            if (!voiceChannelRepository.existsByWorkspaceIdAndChannelId(workspaceId, channelId)) {
                return channelId;
            }
        }

        return "voice-" + UUID.randomUUID();
    }

    @Getter
    @AllArgsConstructor
    public static class UpdateChannelResult {
        private VoiceChannel channel;
        private List<VoiceChannel> channels;
    }

    @Getter
    @AllArgsConstructor
    public static class DeleteChannelResult {
        private VoiceChannel deletedChannel;
        private List<VoiceChannel> channels;
    }
}