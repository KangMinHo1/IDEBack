package com.myide.backend.repository;

import com.myide.backend.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    List<Workspace> findByOwnerId(String ownerId);

    Optional<Workspace> findByUuidAndOwnerId(String uuid, String ownerId);
}