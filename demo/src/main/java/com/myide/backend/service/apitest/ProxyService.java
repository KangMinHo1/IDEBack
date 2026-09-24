package com.myide.backend.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.apitest.ProxyRequest;
import com.myide.backend.dto.apitest.ProxyResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class ProxyService {

    private static final String BODY_NONE = "none";
    private static final String BODY_RAW = "raw";
    private static final String BODY_FORM_DATA = "form-data";
    private static final String BODY_URL_ENCODED = "x-www-form-urlencoded";

    private final RestTemplate restTemplate;
    private final ObjectMapper om;

    public ProxyService(ObjectMapper om) {
        this.om = om;

        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        this.restTemplate =
                new RestTemplate(new JdkClientHttpRequestFactory(httpClient));
    }

    public ProxyResponse forward(ProxyRequest req) {
        long startNs = System.nanoTime();

        try {
            SsrfGuard.validateTargetUrl(req.getUrl());

            HttpMethod method = normalize(req.getMethod());
            if (method == null) {
                throw new IllegalArgumentException(
                        "지원하지 않는 HTTP Method입니다: " + req.getMethod()
                );
            }

            HttpHeaders headers = createRequestHeaders(req.getHeaders());
            HttpEntity<?> entity = createHttpEntity(req, method, headers);

            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    req.getUrl(),
                    method,
                    entity,
                    byte[].class
            );

            long timeMs = elapsedMs(startNs);
            byte[] raw = upstream.getBody();

            return ProxyResponse.builder()
                    .status(upstream.getStatusCode().value())
                    .statusText(reasonPhrase(upstream.getStatusCode().value()))
                    .data(parseJsonIfPossible(raw))
                    .timeMs(timeMs)
                    .headers(flattenHeaders(upstream.getHeaders()))
                    .size(raw == null ? 0L : raw.length)
                    .build();

        } catch (HttpStatusCodeException e) {
            long timeMs = elapsedMs(startNs);
            byte[] raw = e.getResponseBodyAsByteArray();

            return ProxyResponse.builder()
                    .status(e.getStatusCode().value())
                    .statusText(
                            e.getStatusText() == null || e.getStatusText().isBlank()
                                    ? reasonPhrase(e.getStatusCode().value())
                                    : e.getStatusText()
                    )
                    .data(parseJsonIfPossible(raw))
                    .timeMs(timeMs)
                    .headers(flattenHeaders(e.getResponseHeaders()))
                    .size(raw == null ? 0L : raw.length)
                    .build();

        } catch (Exception e) {
            long timeMs = elapsedMs(startNs);

            Map<String, Object> err = new LinkedHashMap<>();
            err.put("message", "proxy failed");
            err.put(
                    "error",
                    e.getMessage() == null
                            ? e.getClass().getSimpleName()
                            : e.getMessage()
            );

            byte[] raw = toJsonBytes(err);

            return ProxyResponse.builder()
                    .status(HttpStatus.BAD_GATEWAY.value())
                    .statusText(HttpStatus.BAD_GATEWAY.getReasonPhrase())
                    .data(err)
                    .timeMs(timeMs)
                    .headers(Map.of(
                            HttpHeaders.CONTENT_TYPE,
                            MediaType.APPLICATION_JSON_VALUE
                    ))
                    .size(raw.length)
                    .build();
        }
    }

    private HttpHeaders createRequestHeaders(Map<String, String> input) {
        HttpHeaders headers = new HttpHeaders();

        if (input == null || input.keySet().stream()
                .noneMatch(k ->
                        k != null
                                && k.equalsIgnoreCase(HttpHeaders.ACCEPT)
                )) {
            headers.setAccept(List.of(
                    MediaType.APPLICATION_JSON,
                    MediaType.ALL
            ));
        }

        if (input != null) {
            input.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    return;
                }

                if (key.equalsIgnoreCase(HttpHeaders.HOST)
                        || key.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                    return;
                }

                headers.set(key.trim(), value);
            });
        }

        return headers;
    }

    private HttpEntity<?> createHttpEntity(
            ProxyRequest req,
            HttpMethod method,
            HttpHeaders headers
    ) {
        boolean canSendBody =
                method != HttpMethod.GET
                        && method != HttpMethod.HEAD
                        && method != HttpMethod.OPTIONS;

        if (!canSendBody) {
            return new HttpEntity<>(headers);
        }

        String bodyType = normalizeBodyType(req.getBodyType());

        if (BODY_FORM_DATA.equals(bodyType)) {
            return createMultipartEntity(req, headers);
        }

        if (BODY_URL_ENCODED.equals(bodyType)) {
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(
                        MediaType.APPLICATION_FORM_URLENCODED
                );
            }

            return new HttpEntity<>(
                    req.getBody() == null ? "" : req.getBody(),
                    headers
            );
        }

        if (BODY_RAW.equals(bodyType)) {
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(
                        contentTypeForRaw(req.getRawType())
                );
            }

            return new HttpEntity<>(
                    req.getBody() == null ? "" : req.getBody(),
                    headers
            );
        }

        return new HttpEntity<>(headers);
    }

    private HttpEntity<MultiValueMap<String, Object>> createMultipartEntity(
            ProxyRequest req,
            HttpHeaders headers
    ) {
        headers.remove(HttpHeaders.CONTENT_TYPE);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> multipart =
                new LinkedMultiValueMap<>();

        if (req.getFormData() != null) {
            for (ProxyRequest.FormDataPart item : req.getFormData()) {
                if (item == null
                        || item.getKey() == null
                        || item.getKey().isBlank()) {
                    continue;
                }

                String key = item.getKey().trim();
                String type = item.getType() == null
                        ? "text"
                        : item.getType()
                        .trim()
                        .toLowerCase(Locale.ROOT);

                if ("file".equals(type)) {
                    if (item.getBase64() == null
                            || item.getBase64().isBlank()) {
                        continue;
                    }

                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder()
                                .decode(item.getBase64());
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(
                                "form-data 파일 Base64 값이 올바르지 않습니다: "
                                        + key
                        );
                    }

                    String fileName =
                            item.getFileName() == null
                                    || item.getFileName().isBlank()
                                    ? "file"
                                    : item.getFileName();

                    ByteArrayResource resource =
                            new ByteArrayResource(bytes) {
                                @Override
                                public String getFilename() {
                                    return fileName;
                                }
                            };

                    HttpHeaders partHeaders = new HttpHeaders();
                    partHeaders.setContentType(
                            parseMediaTypeOrDefault(
                                    item.getContentType(),
                                    MediaType.APPLICATION_OCTET_STREAM
                            )
                    );

                    multipart.add(
                            key,
                            new HttpEntity<>(resource, partHeaders)
                    );
                } else {
                    multipart.add(
                            key,
                            item.getValue() == null
                                    ? ""
                                    : item.getValue()
                    );
                }
            }
        }

        return new HttpEntity<>(multipart, headers);
    }

    private HttpMethod normalize(String method) {
        if (method == null) {
            return null;
        }

        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "GET" -> HttpMethod.GET;
            case "POST" -> HttpMethod.POST;
            case "PUT" -> HttpMethod.PUT;
            case "PATCH" -> HttpMethod.PATCH;
            case "DELETE", "DEL" -> HttpMethod.DELETE;
            case "HEAD" -> HttpMethod.HEAD;
            case "OPTIONS" -> HttpMethod.OPTIONS;
            default -> null;
        };
    }

    private String normalizeBodyType(String bodyType) {
        if (bodyType == null || bodyType.isBlank()) {
            return BODY_NONE;
        }

        return switch (bodyType.trim().toLowerCase(Locale.ROOT)) {
            case BODY_RAW -> BODY_RAW;
            case BODY_FORM_DATA -> BODY_FORM_DATA;
            case BODY_URL_ENCODED -> BODY_URL_ENCODED;
            default -> BODY_NONE;
        };
    }

    private MediaType contentTypeForRaw(String rawType) {
        if (rawType == null) {
            return MediaType.APPLICATION_JSON;
        }

        return switch (rawType.trim().toLowerCase(Locale.ROOT)) {
            case "text" -> MediaType.TEXT_PLAIN;
            case "html" -> MediaType.TEXT_HTML;
            case "xml" -> MediaType.APPLICATION_XML;
            case "json" -> MediaType.APPLICATION_JSON;
            default -> MediaType.APPLICATION_JSON;
        };
    }

    private MediaType parseMediaTypeOrDefault(
            String value,
            MediaType fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return MediaType.parseMediaType(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Map<String, String> flattenHeaders(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();

        headers.forEach((key, values) -> {
            if (key == null) {
                return;
            }

            result.put(
                    key,
                    values == null
                            ? ""
                            : String.join(", ", values)
            );
        });

        return result;
    }

    private Object parseJsonIfPossible(byte[] rawBytes) {
        if (rawBytes == null) {
            return null;
        }

        String raw =
                new String(rawBytes, StandardCharsets.UTF_8).trim();

        if (raw.isEmpty()) {
            return "";
        }

        try {
            return om.readValue(raw, Object.class);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private byte[] toJsonBytes(Object value) {
        try {
            return om.writeValueAsBytes(value);
        } catch (Exception ignored) {
            return String.valueOf(value)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private String reasonPhrase(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null
                ? ""
                : resolved.getReasonPhrase();
    }

    private long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
