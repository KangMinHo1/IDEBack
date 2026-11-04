package com.myide.backend.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.apitest.ProxyRequest;
import com.myide.backend.dto.apitest.ProxyResponse;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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

        this.restTemplate =
                new RestTemplate(new HttpComponentsClientHttpRequestFactory(createGuardedHttpClient()));
    }

    /**
     * SSRF 검사를 연결 단계에 붙인 HTTP 클라이언트.
     *
     * 예전 JDK HttpClient 는 처음 URL 만 SsrfGuard 로 보고 리다이렉트는 검사 없이 따라갔다. 그래서 허용된 서버가
     * 169.254.169.254(EC2 메타데이터) 같은 곳으로 보내면 그대로 붙었다. 또 검사할 때와 연결할 때 DNS 를 따로
     * 물어, 그 사이 응답을 바꾸면(DNS rebinding) 검사를 피할 수 있었다.
     *
     * - 연결할 주소는 GUARDED_DNS_RESOLVER 로만 받는다. 리다이렉트 대상도 새로 연결하므로 여기를 다시 지난다.
     * - 리다이렉트는 이동할 URL 도 SsrfGuard 로 본 뒤에만 따라간다(최대 5번).
     * - 쿠키·압축 해제는 끈다. 예전 클라이언트에 없던 동작이고, 쿠키는 사용자끼리 섞인다.
     */
    static CloseableHttpClient createGuardedHttpClient() {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(GUARDED_DNS_RESOLVER)
                        // 사용자가 넣은 아무 서버나 부르므로, 응답 뒤 연결을 말없이 닫는 서버도 있다.
                        // 다시 쓰기 전에 닫혔는지 보고, 닫혔으면 새로 연결한다(새 연결도 DnsResolver 를 지난다).
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                                .build())
                        .setMaxConnTotal(100)
                        .setMaxConnPerRoute(20)
                        .build())
                .setRedirectStrategy(GUARDED_REDIRECT_STRATEGY)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(true)
                        .setMaxRedirects(5)
                        .build())
                .setRetryStrategy(CONNECTION_ONLY_RETRY_STRATEGY)
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    // 끊긴 연결(keep-alive 로 다시 쓰려던 연결을 서버가 닫은 경우)은 GET 같은 멱등 요청만 한 번 다시 보낸다.
    // 예전 JDK 클라이언트도 그렇게 했다. 429·503 응답을 받고 다시 보내는 것은 끈다 — 사용자가 받은 응답을 그대로 보여 준다.
    private static final DefaultHttpRequestRetryStrategy CONNECTION_ONLY_RETRY_STRATEGY =
            new DefaultHttpRequestRetryStrategy(1, TimeValue.ofSeconds(1)) {
                @Override
                public boolean retryRequest(
                        org.apache.hc.core5.http.HttpResponse response,
                        int execCount,
                        HttpContext context
                ) {
                    return false;
                }
            };

    static final DnsResolver GUARDED_DNS_RESOLVER = new SystemDefaultDnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            try {
                return SsrfGuard.resolveAllowed(host);
            } catch (IllegalArgumentException e) {
                throw new UnknownHostException("blocked target address: " + host + " (" + e.getMessage() + ")");
            }
        }
    };

    private static final DefaultRedirectStrategy GUARDED_REDIRECT_STRATEGY = new DefaultRedirectStrategy() {
        @Override
        public URI getLocationURI(
                org.apache.hc.core5.http.HttpRequest request,
                org.apache.hc.core5.http.HttpResponse response,
                HttpContext context
        ) throws HttpException {
            URI location = super.getLocationURI(request, response, context);
            // Location 이 "/api/..." 처럼 상대 경로로 오면 지금 요청 주소를 기준으로 풀어서 본다.
            URI target;
            try {
                target = location.isAbsolute() ? location : request.getUri().resolve(location);
            } catch (java.net.URISyntaxException e) {
                throw new ProtocolException("invalid redirect location: " + location);
            }
            try {
                SsrfGuard.validateTargetUrl(target.toString());
            } catch (IllegalArgumentException e) {
                throw new ProtocolException("blocked redirect: " + target + " (" + e.getMessage() + ")");
            }
            return location;
        }
    };

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
