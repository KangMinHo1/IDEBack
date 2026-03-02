package com.myide.backend.service;

import com.myide.backend.domain.ApiTestHistory;
import com.myide.backend.dto.HistoryRequest;
import com.myide.backend.dto.HistoryResponse;
import com.myide.backend.repository.ApiHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ApiHistoryService {

    private final ApiHistoryRepository repo;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public ApiHistoryService(ApiHistoryRepository repo) {
        this.repo = repo;
    }

    public HistoryResponse create(HistoryRequest req) {
        ApiTestHistory e = ApiTestHistory.builder()
                .method(req.getMethod())
                .url(req.getUrl())
                .status(req.getStatus())
                .success(req.getSuccess() != null ? req.getSuccess() : (req.getStatus() != null && req.getStatus() < 400))
                .durationMs(req.getDurationMs())
                .build();

        ApiTestHistory saved = repo.save(e);
        return toResponse(saved);
    }

    public List<HistoryResponse> list(int limit) {
        int l = Math.max(1, Math.min(limit, 50)); // 1~50 제한
        return repo.findTop50ByOrderByIdDesc().stream()
                .limit(l)
                .map(this::toResponse)
                .toList();
    }

    private HistoryResponse toResponse(ApiTestHistory e) {
        return HistoryResponse.builder()
                .id(e.getId())
                .method(e.getMethod())
                .url(e.getUrl())
                .status(e.getStatus())
                .success(e.getSuccess())
                .durationMs(e.getDurationMs())
                .createdAt(e.getCreatedAt() == null ? null : e.getCreatedAt().format(ISO))
                .build();
    }
}