package com.myide.backend.service.apitest;

import com.myide.backend.domain.ApiSavedTest;
import com.myide.backend.dto.apitest.SavedTestRequest;
import com.myide.backend.dto.apitest.SavedTestResponse;
import com.myide.backend.repository.ApiSavedTestRepository;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ApiSavedTestService {

    private final ApiSavedTestRepository repo;
    private final JsonStore jsonStore;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public ApiSavedTestService(ApiSavedTestRepository repo, JsonStore jsonStore) {
        this.repo = repo;
        this.jsonStore = jsonStore;
    }

    public SavedTestResponse create(SavedTestRequest req) {
        ApiSavedTest entity = ApiSavedTest.builder()
                .title(req.getTitle())
                .method(req.getMethod())
                .url(req.getUrl())
                .paramsJson(jsonStore.toJson(req.getParams()))
                .headersJson(jsonStore.toJson(req.getHeaders()))
                .body(req.getBody())
                .build();

        ApiSavedTest saved = repo.save(entity);
        return toResponse(saved);
    }

    public List<SavedTestResponse> list() {
        return repo.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(this::toResponse)
                .toList();
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    private SavedTestResponse toResponse(ApiSavedTest e) {
        return SavedTestResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .method(e.getMethod())
                .url(e.getUrl())
                .params(jsonStore.fromJson(e.getParamsJson()))
                .headers(jsonStore.fromJson(e.getHeadersJson()))
                .body(e.getBody() == null ? "" : e.getBody())
                .createdAt(e.getCreatedAt() == null ? null : e.getCreatedAt().format(ISO))
                .build();
    }
}