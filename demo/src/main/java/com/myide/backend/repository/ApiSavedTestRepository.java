package com.myide.backend.repository;

import com.myide.backend.domain.ApiSavedTest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiSavedTestRepository extends JpaRepository<ApiSavedTest, Long> {
}