package com.myide.backend.service.apitest;

import com.myide.backend.domain.ApiTestHistory;
import com.myide.backend.dto.apitest.HistoryRequest;
import com.myide.backend.dto.apitest.HistoryResponse;
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
        Integer status = req.getStatus();

        Boolean success = req.getSuccess();
        if (success == null && status != null) {
            success = status >= 200 && status < 400;
        }

        ApiTestHistory e = ApiTestHistory.builder()
                .method(req.getMethod())
                .url(req.getUrl())
                .status(status)
                .success(success)
                .durationMs(req.getDurationMs())
                .responseBody(req.getResponseBody())
                .build();

        ApiTestHistory saved = repo.save(e);
        return toResponse(saved);
    }

    public List<HistoryResponse> list(int limit) {
        int l = Math.max(1, Math.min(limit, 50));

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
                .responseBody(e.getResponseBody())
                .createdAt(e.getCreatedAt() == null ? null : e.getCreatedAt().format(ISO))
                .build();
    }
}