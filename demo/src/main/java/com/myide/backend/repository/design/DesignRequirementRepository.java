package com.myide.backend.repository.design;

import com.myide.backend.domain.design.DesignRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DesignRequirementRepository extends JpaRepository<DesignRequirement, Long> {

    Optional<DesignRequirement> findByUuid(String uuid);

    List<DesignRequirement> findByWorkspace_UuidOrderByCreatedAtAsc(String workspaceUuid);

    boolean existsByUuidAndWorkspace_Uuid(String uuid, String workspaceUuid);
}