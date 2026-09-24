package com.myide.backend.service.apitest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.ApiSavedTest;
import com.myide.backend.dto.apitest.AssertionDto;
import com.myide.backend.dto.apitest.SavedTestRequest;
import com.myide.backend.dto.apitest.SavedTestResponse;
import com.myide.backend.dto.apitest.UrlEncodedItemDto;
import com.myide.backend.repository.ApiSavedTestRepository;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Service
public class ApiSavedTestService {

    private final ApiSavedTestRepository repo;
    private final JsonStore jsonStore;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public ApiSavedTestService(
            ApiSavedTestRepository repo,
            JsonStore jsonStore,
            ObjectMapper objectMapper
    ) {
        this.repo = repo;
        this.jsonStore = jsonStore;
        this.objectMapper = objectMapper;
    }

    public SavedTestResponse create(SavedTestRequest req) {
        ApiSavedTest entity = ApiSavedTest.builder()
                .title(req.getTitle())
                .method(req.getMethod())
                .url(req.getUrl())
                .paramsJson(jsonStore.toJson(req.getParams()))
                .headersJson(jsonStore.toJson(req.getHeaders()))
                .body(req.getBody())
                .bodyMode(defaultText(req.getBodyMode(), "none"))
                .rawType(defaultText(req.getRawType(), "json"))
                .urlEncodedJson(toJson(req.getUrlEncoded()))
                .authType(defaultText(req.getAuthType(), "none"))
                .bearerToken(req.getBearerToken())
                .basicUsername(req.getBasicUsername())
                .basicPassword(req.getBasicPassword())
                .apiKeyName(req.getApiKeyName())
                .apiKeyValue(req.getApiKeyValue())
                .apiKeyLocation(defaultText(req.getApiKeyLocation(), "header"))
                .assertionsJson(toJson(req.getAssertions()))
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
                .bodyMode(defaultText(e.getBodyMode(), "none"))
                .rawType(defaultText(e.getRawType(), "json"))
                .urlEncoded(readUrlEncoded(e.getUrlEncodedJson()))
                .authType(defaultText(e.getAuthType(), "none"))
                .bearerToken(nullToEmpty(e.getBearerToken()))
                .basicUsername(nullToEmpty(e.getBasicUsername()))
                .basicPassword(nullToEmpty(e.getBasicPassword()))
                .apiKeyName(
                        e.getApiKeyName() == null || e.getApiKeyName().isBlank()
                                ? "X-API-Key"
                                : e.getApiKeyName()
                )
                .apiKeyValue(nullToEmpty(e.getApiKeyValue()))
                .apiKeyLocation(defaultText(e.getApiKeyLocation(), "header"))
                .assertions(readAssertions(e.getAssertionsJson()))
                .createdAt(
                        e.getCreatedAt() == null
                                ? null
                                : e.getCreatedAt().format(ISO)
                )
                .build();
    }

    private List<UrlEncodedItemDto> readUrlEncoded(String json) {
        return fromJson(
                json,
                new TypeReference<List<UrlEncodedItemDto>>() {},
                Collections.emptyList()
        );
    }

    private List<AssertionDto> readAssertions(String json) {
        return fromJson(
                json,
                new TypeReference<List<AssertionDto>>() {},
                Collections.emptyList()
        );
    }

    private String toJson(Object value) {
        if (value == null) {
            return "[]";
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "API 테스트 저장 데이터를 JSON으로 변환하지 못했습니다.",
                    e
            );
        }
    }

    private <T> T fromJson(
            String json,
            TypeReference<T> type,
            T fallback
    ) {
        if (json == null || json.isBlank()) {
            return fallback;
        }

        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
