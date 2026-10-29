package com.myide.backend.repository;

import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.dto.mypage.DateCountRow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /*
     * 목록 조회용.
     *
     * 응답이 일지마다 일정·작성자를 읽으므로 함께 가져온다
     * (빠지면 일지 수만큼 일정·사용자 조회가 따로 나간다).
     */
    @EntityGraph(attributePaths = {"schedule", "createdBy"})
    List<Devlog> findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(
            String workspaceUuid
    );

    // 작성자 필터용. 위 목록 조회와 같다.
    @EntityGraph(attributePaths = {"schedule", "createdBy"})
    List<Devlog> findByWorkspace_UuidAndCreatedBy_IdOrderByWorkedDateDescCreatedAtDesc(
            String workspaceUuid,
            Long createdById
    );

    @EntityGraph(attributePaths = {"schedule", "createdBy"})
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