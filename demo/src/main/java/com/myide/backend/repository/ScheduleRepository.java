package com.myide.backend.repository;

import com.myide.backend.domain.Schedule;
import com.myide.backend.domain.Schedule.ScheduleCategory;
import com.myide.backend.domain.Schedule.ScheduleStatus;
import com.myide.backend.domain.Schedule.ScheduleType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    List<Schedule> findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            ScheduleType type,
            String workspaceUuid,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );

    List<Schedule> findByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
            ScheduleType type,
            String workspaceUuid,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );

    List<Schedule> findByTypeAndWorkspace_UuidAndCategoryAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAscIdDesc(
            ScheduleType type,
            String workspaceUuid,
            ScheduleCategory category,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );

    long countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            ScheduleType type,
            String workspaceUuid,
            LocalDate rangeEnd,
            LocalDate rangeStart
    );

    long countByTypeAndWorkspace_UuidAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCategory(
            ScheduleType type,
            String workspaceUuid,
            LocalDate rangeEnd,
            LocalDate rangeStart,
            ScheduleCategory category
    );

    long countByTypeAndWorkspace_Uuid(
            ScheduleType type,
            String workspaceUuid
    );

    long countByTypeAndWorkspace_UuidAndStatus(
            ScheduleType type,
            String workspaceUuid,
            ScheduleStatus status
    );

    List<Schedule> findByTypeAndWorkspace_UuidOrderByStartDateDescIdDesc(
            ScheduleType type,
            String workspaceUuid,
            Pageable pageable
    );
}
