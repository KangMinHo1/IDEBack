package com.myide.backend.repository;

import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.dto.mypage.DateCountRow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DevlogRepository
        extends JpaRepository<Devlog, Long> {

    @Query(value = """
        SELECT
            d.worked_date AS date,
            COUNT(*) AS count
        FROM devlogs d
        WHERE d.created_by = :userId
          AND d.worked_date >= :startDate
          AND d.worked_date <= :endDate
        GROUP BY d.worked_date
        ORDER BY d.worked_date
    """, nativeQuery = true)
    List<DateCountRow> countMyDevlogsByWorkedDate(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /*
     * 마이페이지 최근 활동용
     *
     * 실제 작성자인 createdBy 기준.
     */
    List<Devlog> findByCreatedBy_IdOrderByCreatedAtDesc(
            Long userId,
            Pageable pageable
    );

    Optional<Devlog> findByUuid(String uuid);

    Optional<Devlog> findByUuidAndWorkspace_Uuid(
            String uuid,
            String workspaceUuid
    );

    List<Devlog> findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(
            String workspaceUuid
    );

    List<Devlog> findBySchedule_UuidOrderByWorkedDateDescCreatedAtDesc(
            String scheduleUuid
    );

    long countBySchedule_Uuid(
            String scheduleUuid
    );

    boolean existsBySchedule_Uuid(
            String scheduleUuid
    );
}