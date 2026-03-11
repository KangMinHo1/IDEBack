package com.myide.backend.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.apitest.ProxyRequest;
import com.myide.backend.dto.apitest.ProxyResponse;
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

        try {
            SsrfGuard.validateTargetUrl(req.getUrl());

            HttpMethod method = normalize(req.getMethod());
            if (method == null) throw new IllegalArgumentException("invalid method");

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

            if (req.getHeaders() != null) {
                req.getHeaders().forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null) headers.add(k, v);
                });
            }

            boolean hasBodyMethod = (method != HttpMethod.GET && method != HttpMethod.HEAD);
            String body = req.getBody();
            if (hasBodyMethod && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }

            HttpEntity<String> entity = hasBodyMethod
                    ? new HttpEntity<>(body, headers)
                    : new HttpEntity<>(headers);

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
            long timeMs = (System.nanoTime() - startNs) / 1_000_000;
            byte[] raw = e.getResponseBodyAsByteArray();
            Object parsed = parseJsonIfPossible(raw);
            return new ProxyResponse(e.getStatusCode().value(), parsed, timeMs);

        } catch (Exception e) {
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