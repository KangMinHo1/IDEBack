package com.myide.backend.repository;

import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.dto.mypage.DateCountRow;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    Optional<Schedule> findByUuid(String uuid);

    Optional<Schedule> findByUuidAndWorkspace_Uuid(String uuid, String workspaceUuid);

    List<Schedule> findByWorkspace_UuidOrderByStartDateAscCreatedAtDesc(String workspaceUuid);

    List<Schedule> findByWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscCreatedAtDesc(
            String workspaceUuid,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );
    List<Schedule> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(
            LocalDate today,
            LocalDate today2
    );

    boolean existsByUuidAndWorkspace_Uuid(String uuid, String workspaceUuid);

    @Query(value = """
        SELECT
            DATE(s.updated_at) AS date,
            COUNT(*) AS count
        FROM schedules s
        WHERE s.created_by = :userId
          AND s.status = 'DONE'
          AND s.updated_at >= :startDateTime
          AND s.updated_at < :endDateTime
        GROUP BY DATE(s.updated_at)
        ORDER BY DATE(s.updated_at)
    """, nativeQuery = true)
    List<DateCountRow> countMyDoneSchedulesByUpdatedDate(
            @Param("userId") Long userId,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime
    );
}