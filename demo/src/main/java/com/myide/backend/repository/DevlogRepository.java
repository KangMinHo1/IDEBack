package com.myide.backend.repository;

import com.myide.backend.domain.Devlog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DevlogRepository extends JpaRepository<Devlog, Long> {

    Optional<Devlog> findByIdAndProjectId(Long id, Long projectId);

    List<Devlog> findByProjectId(Long projectId);
}