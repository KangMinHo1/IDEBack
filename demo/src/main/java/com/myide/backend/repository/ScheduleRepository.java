package com.myide.backend.repository;

import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.schedule.ScheduleStatus;
import com.myide.backend.dto.mypage.DateCountRow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository
        extends JpaRepository<Schedule, Long> {

    Optional<Schedule> findByUuid(
            String uuid
    );

    Optional<Schedule> findByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    List<Schedule> findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(
            String workspaceUuid
    );

    List<Schedule>
    findByWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscCreatedAtDesc(
            String workspaceUuid,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );

    List<Schedule>
    findByStartDateLessThanEqualAndEndDateGreaterThanEqual(
            LocalDate today,
            LocalDate today2
    );

    boolean existsByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    /*
     * 히트맵용.
     *
     * 이전:
     * updated_at 기준
     *
     * 변경:
     * 실제 완료 시간 completed_at 기준
     */
    @Query(value = """
        SELECT
            DATE(s.completed_at) AS date,
            COUNT(*) AS count
        FROM schedules s
        WHERE s.created_by = :userId
          AND s.status = 'DONE'
          AND s.completed_at IS NOT NULL
          AND s.completed_at >= :startDateTime
          AND s.completed_at < :endDateTime
        GROUP BY DATE(s.completed_at)
        ORDER BY DATE(s.completed_at)
    """, nativeQuery = true)
    List<DateCountRow> countMyDoneSchedulesByCompletedDate(
            @Param("userId") Long userId,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime
    );

    /*
     * 최근 활동용.
     */
    List<Schedule>
    findByCreatedBy_IdAndStatusAndCompletedAtIsNotNullOrderByCompletedAtDesc(
            Long userId,
            ScheduleStatus status,
            Pageable pageable
    );
}