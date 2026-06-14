package com.myide.backend.repository;

import com.myide.backend.domain.notification.Notification;
import com.myide.backend.domain.notification.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    long countByReceiver_IdAndReadFalse(Long receiverId);

    Optional<Notification> findByIdAndReceiver_Id(Long id, Long receiverId);

    Page<Notification> findByReceiver_IdOrderByCreatedAtDesc(
            Long receiverId,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndReadOrderByCreatedAtDesc(
            Long receiverId,
            boolean read,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndTypeOrderByCreatedAtDesc(
            Long receiverId,
            NotificationType type,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndTypeAndReadOrderByCreatedAtDesc(
            Long receiverId,
            NotificationType type,
            boolean read,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long receiverId,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndReadAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long receiverId,
            boolean read,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long receiverId,
            NotificationType type,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    );

    Page<Notification> findByReceiver_IdAndTypeAndReadAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long receiverId,
            NotificationType type,
            boolean read,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable
    );
}