package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.ProxyRequest;
import com.myide.backend.dto.ProxyResponse;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ProxyService {

    private final RestTemplate restTemplate;
    private final ObjectMapper om;

    public ProxyService(ObjectMapper om) {
        this.om = om;
        this.restTemplate = new RestTemplate();
    }

    public ProxyResponse forward(ProxyRequest req) {
        long startNs = System.nanoTime();

        // ✅ SSRF 최소 방어
        SsrfGuard.validateTargetUrl(req.getUrl());

        HttpMethod method = normalize(req.getMethod());
        if (method == null) throw new IllegalArgumentException("invalid method");

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

        // 프론트에서 넘어온 헤더 추가
        if (req.getHeaders() != null) {
            req.getHeaders().forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) headers.add(k, v);
            });
        }

        // 바디 있는 메서드면 content-type 기본값
        boolean hasBodyMethod = (method != HttpMethod.GET && method != HttpMethod.HEAD);
        String body = req.getBody();
        if (hasBodyMethod) {
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
        }

        HttpEntity<String> entity = hasBodyMethod ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);

        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    req.getUrl(),
                    method,
                    entity,
                    byte[].class
            );

            long timeMs = (System.nanoTime() - startNs) / 1_000_000;
            Object parsed = parseJsonIfPossible(upstream.getBody());

            return new ProxyResponse(upstream.getStatusCode().value(), parsed, timeMs);

        } catch (HttpStatusCodeException e) {
            // ✅ 업스트림이 4xx/5xx를 줘도 "프록시 실패(502)"로 바꾸지 말고 그대로 전달
            long timeMs = (System.nanoTime() - startNs) / 1_000_000;
            byte[] raw = e.getResponseBodyAsByteArray();
            Object parsed = parseJsonIfPossible(raw);

            return new ProxyResponse(e.getStatusCode().value(), parsed, timeMs);

        } catch (Exception e) {
            // ✅ 진짜 네트워크/인증서/연결 실패 같은 케이스만 502
            long timeMs = (System.nanoTime() - startNs) / 1_000_000;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", "proxy failed");
            err.put("error", e.getMessage());
            return new ProxyResponse(502, err, timeMs);
        }
    }

    private HttpMethod normalize(String m) {
        if (m == null) return null;
        return switch (m.toUpperCase(Locale.ROOT)) {
            case "GET" -> HttpMethod.GET;
            case "POST" -> HttpMethod.POST;
            case "PUT" -> HttpMethod.PUT;
            case "DELETE", "DEL" -> HttpMethod.DELETE;
            default -> null;
        };
    }

    private Object parseJsonIfPossible(byte[] rawBytes) {
        if (rawBytes == null) return null;
        String raw = new String(rawBytes, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) return "";

        try {
            if (raw.startsWith("{")) return om.readValue(raw, Map.class);
            if (raw.startsWith("[")) return om.readValue(raw, List.class);
        } catch (Exception ignore) {}
        return raw;
    }
}