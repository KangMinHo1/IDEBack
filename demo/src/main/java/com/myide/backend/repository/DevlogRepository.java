package com.myide.backend.repository;

import com.myide.backend.domain.Devlog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DevlogRepository extends JpaRepository<Devlog, Long> {

    long countByProject_Id(Long projectId);

    List<Devlog> findByProject_Id(Long projectId);

    Optional<Devlog> findByIdAndProject_Id(Long id, Long projectId);
}