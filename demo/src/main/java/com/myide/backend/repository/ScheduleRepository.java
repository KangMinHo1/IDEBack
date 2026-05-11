package com.myide.backend.repository;

import com.myide.backend.domain.schedule.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

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

    boolean existsByUuidAndWorkspace_Uuid(String uuid, String workspaceUuid);
}