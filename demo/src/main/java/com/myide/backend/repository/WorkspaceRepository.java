package com.myide.backend.repository;

import com.myide.backend.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {
    List<String> findAllByOwnerId(String ownerId);
}