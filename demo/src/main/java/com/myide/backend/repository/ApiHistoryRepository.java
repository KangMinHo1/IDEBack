package com.myide.backend.repository;

import com.myide.backend.domain.ApiTestHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApiHistoryRepository extends JpaRepository<ApiTestHistory, Long> {
    List<ApiTestHistory> findTop10ByOrderByIdDesc();
    List<ApiTestHistory> findTop50ByOrderByIdDesc();
}