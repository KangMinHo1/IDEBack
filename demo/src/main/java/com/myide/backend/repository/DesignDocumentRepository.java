package com.myide.backend.repository;

import com.myide.backend.domain.design.DesignDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DesignDocumentRepository extends JpaRepository<DesignDocument, Long> {

    Optional<DesignDocument> findByWorkspace_Uuid(String workspaceUuid);

    Optional<DesignDocument> findByUuid(String uuid);

    boolean existsByWorkspace_Uuid(String workspaceUuid);
}