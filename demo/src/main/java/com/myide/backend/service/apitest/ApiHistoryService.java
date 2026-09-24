package com.myide.backend.service.apitest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.ApiTestHistory;
import com.myide.backend.dto.apitest.HistoryRequest;
import com.myide.backend.dto.apitest.HistoryResponse;
import com.myide.backend.repository.ApiHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ApiHistoryService {

    private final ApiHistoryRepository repo;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public ApiHistoryService(
            ApiHistoryRepository repo,
            ObjectMapper objectMapper
    ) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public HistoryResponse create(HistoryRequest req) {
        Integer status = req.getStatus();

        Boolean success = req.getSuccess();
        if (success == null && status != null) {
            success = status >= 200 && status < 400;
        }

        ApiTestHistory entity = ApiTestHistory.builder()
                .method(req.getMethod())
                .url(req.getUrl())
                .status(status)
                .statusText(req.getStatusText())
                .success(success)
                .durationMs(req.getDurationMs())
                .responseSize(req.getResponseSize())
                .responseBody(req.getResponseBody())
                .responseHeadersJson(toJson(req.getResponseHeaders()))
                .build();

        ApiTestHistory saved = repo.save(entity);
        return toResponse(saved);
    }

    public List<HistoryResponse> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));

        return repo.findTop50ByOrderByIdDesc().stream()
                .limit(safeLimit)
                .map(this::toResponse)
                .toList();
    }

    private HistoryResponse toResponse(ApiTestHistory e) {
        return HistoryResponse.builder()
                .id(e.getId())
                .method(e.getMethod())
                .url(e.getUrl())
                .status(e.getStatus())
                .statusText(e.getStatusText())
                .success(e.getSuccess())
                .durationMs(e.getDurationMs())
                .responseSize(e.getResponseSize())
                .responseBody(e.getResponseBody())
                .responseHeaders(readHeaders(e.getResponseHeadersJson()))
                .createdAt(
                        e.getCreatedAt() == null
                                ? null
                                : e.getCreatedAt().format(ISO)
                )
                .build();
    }

    private String toJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "{}";
        }

        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, String> readHeaders(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<Map<String, String>>() {}
            );
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
