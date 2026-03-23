package com.myide.backend.repository;

import com.myide.backend.domain.Schedule;
import com.myide.backend.domain.Schedule.ScheduleCategory;
import com.myide.backend.domain.Schedule.ScheduleType;
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
}