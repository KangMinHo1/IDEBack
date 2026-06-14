package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.notification.Notification;
import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import com.myide.backend.dto.notification.NotificationResponse;
import com.myide.backend.repository.NotificationRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceMemberRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public Page<NotificationResponse> getNotifications(
            Long userId,
            NotificationType type,
            Boolean read,
            LocalDate from,
            LocalDate to,
            Pageable pageable
    ) {
        validateUserId(userId);

        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;

        Page<Notification> page;

        if (fromDateTime != null && toDateTime != null) {
            if (type != null && read != null) {
                page = notificationRepository
                        .findByReceiver_IdAndTypeAndReadAndCreatedAtBetweenOrderByCreatedAtDesc(
                                userId, type, read, fromDateTime, toDateTime, pageable
                        );
            } else if (type != null) {
                page = notificationRepository
                        .findByReceiver_IdAndTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
                                userId, type, fromDateTime, toDateTime, pageable
                        );
            } else if (read != null) {
                page = notificationRepository
                        .findByReceiver_IdAndReadAndCreatedAtBetweenOrderByCreatedAtDesc(
                                userId, read, fromDateTime, toDateTime, pageable
                        );
            } else {
                page = notificationRepository
                        .findByReceiver_IdAndCreatedAtBetweenOrderByCreatedAtDesc(
                                userId, fromDateTime, toDateTime, pageable
                        );
            }
        } else {
            if (type != null && read != null) {
                page = notificationRepository
                        .findByReceiver_IdAndTypeAndReadOrderByCreatedAtDesc(
                                userId, type, read, pageable
                        );
            } else if (type != null) {
                page = notificationRepository
                        .findByReceiver_IdAndTypeOrderByCreatedAtDesc(
                                userId, type, pageable
                        );
            } else if (read != null) {
                page = notificationRepository
                        .findByReceiver_IdAndReadOrderByCreatedAtDesc(
                                userId, read, pageable
                        );
            } else {
                page = notificationRepository
                        .findByReceiver_IdOrderByCreatedAtDesc(userId, pageable);
            }
        }

        return page.map(NotificationResponse::from);
    }

    public Map<String, Long> getUnreadCount(Long userId) {
        validateUserId(userId);

        long count = notificationRepository.countByReceiver_IdAndReadFalse(userId);

        Map<String, Long> response = new LinkedHashMap<>();
        response.put("unreadCount", count);

        return response;
    }

    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        validateUserId(userId);

        Notification notification = notificationRepository.findByIdAndReceiver_Id(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));

        notification.markAsRead();
    }

    @Transactional
    public void markAllAsRead(Long userId) {
        validateUserId(userId);

        List<Notification> notifications = notificationRepository
                .findByReceiver_IdAndReadOrderByCreatedAtDesc(userId, false, Pageable.unpaged())
                .getContent();

        notifications.forEach(Notification::markAsRead);
    }

    @Transactional
    public void deleteNotification(Long notificationId, Long userId) {
        validateUserId(userId);

        Notification notification = notificationRepository.findByIdAndReceiver_Id(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));

        notificationRepository.delete(notification);
    }

    @Transactional
    public void notifyUser(
            Long receiverId,
            String workspaceId,
            NotificationType type,
            String title,
            String message,
            String targetUrl
    ) {
        if (receiverId == null) return;

        User receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림 수신자를 찾을 수 없습니다."));

        Workspace workspace = null;

        if (workspaceId != null && !workspaceId.isBlank()) {
            workspace = workspaceRepository.findById(workspaceId)
                    .orElse(null);
        }

        Notification notification = Notification.builder()
                .receiver(receiver)
                .workspace(workspace)
                .type(type)
                .title(title)
                .message(message)
                .targetUrl(targetUrl)
                .build();

        notificationRepository.save(notification);
    }

    @Transactional
    public void notifyWorkspaceMembersExcept(
            String workspaceId,
            Long excludedUserId,
            NotificationType type,
            String title,
            String message,
            String targetUrl
    ) {
        if (workspaceId == null || workspaceId.isBlank()) return;

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."));

        List<WorkspaceMember> members = workspaceMemberRepository.findByWorkspace_UuidAndStatus(
                workspaceId,
                WorkspaceMember.JoinStatus.ACCEPTED
        );

        members.stream()
                .map(WorkspaceMember::getUser)
                .filter(user -> excludedUserId == null || !user.getId().equals(excludedUserId))
                .forEach(receiver -> {
                    Notification notification = Notification.builder()
                            .receiver(receiver)
                            .workspace(workspace)
                            .type(type)
                            .title(title)
                            .message(message)
                            .targetUrl(targetUrl)
                            .build();

                    notificationRepository.save(notification);
                });

        User owner = workspace.getOwner();

        boolean ownerAlreadyIncluded = members.stream()
                .anyMatch(member -> member.getUser().getId().equals(owner.getId()));

        if (!ownerAlreadyIncluded && (excludedUserId == null || !owner.getId().equals(excludedUserId))) {
            Notification notification = Notification.builder()
                    .receiver(owner)
                    .workspace(workspace)
                    .type(type)
                    .title(title)
                    .message(message)
                    .targetUrl(targetUrl)
                    .build();

            notificationRepository.save(notification);
        }
    }

    @Transactional
    public void notifyAllUsersExcept(
            Long excludedUserId,
            NotificationType type,
            String title,
            String message,
            String targetUrl
    ) {
        userRepository.findAll()
                .stream()
                .filter(user -> excludedUserId == null || !user.getId().equals(excludedUserId))
                .forEach(receiver -> {
                    Notification notification = Notification.builder()
                            .receiver(receiver)
                            .workspace(null)
                            .type(type)
                            .title(title)
                            .message(message)
                            .targetUrl(targetUrl)
                            .build();

                    notificationRepository.save(notification);
                });
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
    }
}