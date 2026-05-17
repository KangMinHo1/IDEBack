package com.myide.backend.repository;

import com.myide.backend.domain.design.DesignApiSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DesignApiSpecRepository extends JpaRepository<DesignApiSpec, Long> {

    Optional<DesignApiSpec> findByUuid(String uuid);

    List<DesignApiSpec> findByWorkspace_UuidOrderByCreatedAtAsc(String workspaceUuid);

    boolean existsByUuidAndWorkspace_Uuid(String uuid, String workspaceUuid);
}