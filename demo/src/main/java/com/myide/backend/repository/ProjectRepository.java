package com.myide.backend.repository;

import com.myide.backend.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByWorkspaceUuidOrderByUpdatedAtDesc(String workspaceUuid);

    Optional<Project> findByIdAndWorkspaceUuid(Long id, String workspaceUuid);
}