package com.myide.backend.controller;

import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.dto.notification.NotificationResponse;
import com.myide.backend.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Page<NotificationResponse> getNotifications(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return notificationService.getNotifications(userId, type, read, from, to, pageable);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> getUnreadCount(
            @AuthenticationPrincipal Long userId
    ) {
        return notificationService.getUnreadCount(userId);
    }

    @PatchMapping("/{notificationId}/read")
    public void markAsRead(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal Long userId
    ) {
        notificationService.markAsRead(notificationId, userId);
    }

    @PatchMapping("/read-all")
    public void markAllAsRead(
            @AuthenticationPrincipal Long userId
    ) {
        notificationService.markAllAsRead(userId);
    }

    @DeleteMapping("/{notificationId}")
    public void deleteNotification(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal Long userId
    ) {
        notificationService.deleteNotification(notificationId, userId);
    }
}