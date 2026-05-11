package com.myide.backend.repository;

import com.myide.backend.domain.devlog.Devlog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DevlogRepository extends JpaRepository<Devlog, Long> {

    Optional<Devlog> findByUuid(String uuid);

    Optional<Devlog> findByUuidAndWorkspace_Uuid(String uuid, String workspaceUuid);

    List<Devlog> findByWorkspace_UuidOrderByWorkedDateDescCreatedAtDesc(String workspaceUuid);

    List<Devlog> findBySchedule_UuidOrderByWorkedDateDescCreatedAtDesc(String scheduleUuid);

    long countBySchedule_Uuid(String scheduleUuid);

    boolean existsBySchedule_Uuid(String scheduleUuid);
}