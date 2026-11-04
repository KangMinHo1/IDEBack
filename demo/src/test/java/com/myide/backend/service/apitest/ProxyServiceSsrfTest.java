package com.myide.backend.service.apitest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.apitest.ProxyRequest;
import com.myide.backend.dto.apitest.ProxyResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * API 테스터 프록시의 SSRF 방어. 실제 서버 대신 127.0.0.1 에 띄운 작은 HTTP 서버로 확인한다.
 * 외부 HTTPS·미리보기 도메인 호출은 네트워크가 필요해 -Dproxy.online=true 일 때만 돈다.
 */
class ProxyServiceSsrfTest {

    private static HttpServer server;
    private static final java.util.concurrent.atomic.AtomicInteger unavailableHits = new java.util.concurrent.atomic.AtomicInteger();
    private static String base;
    private final ProxyService proxyService = new ProxyService(new ObjectMapper());

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/reservations", ex -> {
            ex.getResponseHeaders().add("X-Test", "1");
            send(ex, 200, "{\"ok\":true,\"path\":\"" + ex.getRequestURI() + "\"}");
        });
        server.createContext("/unavailable", ex -> {
            unavailableHits.incrementAndGet();
            ex.getResponseHeaders().add("Retry-After", "1");
            send(ex, 503, "{\"message\":\"busy\"}");
        });
        server.createContext("/missing", ex -> send(ex, 404, "{\"message\":\"not found\"}"));
        server.createContext("/safe-redirect", ex -> redirect(ex, "/api/reservations?from=redirect"));
        server.createContext("/to-metadata", ex -> redirect(ex, "http://169.254.169.254/latest/meta-data/"));
        // 169.254.169.254 를 10진수 하나로 쓴 것
        server.createContext("/to-metadata-decimal", ex -> redirect(ex, "http://2852039166/latest/meta-data/"));
        server.createContext("/to-link-local-v6", ex -> redirect(ex, "http://[fe80::1]/"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        // 길이 -1 은 JDK HttpServer 가 본문 길이를 안 적어 연결을 다시 쓸 수 없다. 실제 서버처럼 빈 본문을 보낸다.
        ex.sendResponseHeaders(302, 0);
        ex.getResponseBody().close();
        ex.close();
    }

    private ProxyResponse get(String url) {
        ProxyRequest req = new ProxyRequest();
        req.setUrl(url);
        req.setMethod("GET");
        return proxyService.forward(req);
    }

    private static String errorOf(ProxyResponse res) {
        assertThat(res.getData()).isInstanceOf(Map.class);
        return String.valueOf(((Map<?, ?>) res.getData()).get("error"));
    }

    /* ── SsrfGuard ── */

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.1.1/",
            "http://2852039166/",                       // 169.254.169.254 (10진수)
            "http://[::ffff:169.254.169.254]/",         // IPv4 매핑 IPv6
            "http://[fe80::1]/",
            "http://[fd00:ec2::254]/",                  // AWS IPv6 메타데이터
            "http://0.0.0.0:8080/",
            "http://224.0.0.1/",
            "ftp://127.0.0.1/",
    })
    void guardBlocksMetadataLinkLocalAndOthers(String url) {
        assertThatThrownBy(() -> SsrfGuard.validateTargetUrl(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 시연 설정(ALLOW_PRIVATE_NETWORK_FOR_DEMO)이 허용하던 주소와 공인 주소는 그대로 허용된다.
    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:34567/api/reservations",
            "http://localhost:8080/api/history",
            "http://172.16.99.104:34567/api/reservations",
            "http://10.0.0.5:34567/",
            "http://192.168.0.10:34567/",
            "https://3.38.77.96/preview/34567/api/reservations",
    })
    void guardKeepsExistingAllowance(String url) {
        assertThatCode(() -> SsrfGuard.validateTargetUrl(url)).doesNotThrowAnyException();
    }

    /* ── 연결 단계(DnsResolver) ── */

    @Test
    void resolverIsTheOnlyAddressSource() throws Exception {
        assertThatThrownBy(() -> ProxyService.GUARDED_DNS_RESOLVER.resolve("169.254.169.254"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("blocked target address");
        assertThat(ProxyService.GUARDED_DNS_RESOLVER.resolve("127.0.0.1")).isNotEmpty();
    }

    // SsrfGuard.validateTargetUrl 을 거치지 않고 클라이언트로 바로 보내도 연결 전에 막힌다(IP 주소도 DnsResolver 를 지난다).
    @Test
    void clientRefusesBlockedAddressBeforeConnecting() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (CloseableHttpClient client = ProxyService.createGuardedHttpClient()) {
                assertThatThrownBy(() -> client.execute(new HttpGet("http://169.254.169.254/latest/meta-data/"), r -> r.getCode()))
                        .isInstanceOf(UnknownHostException.class)
                        .hasMessageContaining("blocked target address");
            }
        });
    }

    /* ── ProxyService ── */

    @Test
    void forwardsNormalRequestWithSameResponseShape() {
        ProxyResponse res = get(base + "/api/reservations?date=2026-11-03");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getStatusText()).isEqualTo("OK");
        assertThat(res.getData()).isEqualTo(Map.of("ok", true, "path", "/api/reservations?date=2026-11-03"));
        // 헤더 이름의 대소문자는 서버가 보낸 대로다(JDK HttpServer 는 X-test 로 보낸다).
        assertThat(res.getHeaders()).hasEntrySatisfying("X-test", value -> assertThat(value).isEqualTo("1"));
        assertThat(res.getSize()).isGreaterThan(0);
    }

    @Test
    void keepsErrorStatusAndBody() {
        ProxyResponse res = get(base + "/missing");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getData()).isEqualTo(Map.of("message", "not found"));
    }

    // 503 을 받았다고 다시 보내지 않는다(예전 클라이언트와 같다). 사용자는 받은 응답을 그대로 본다.
    @Test
    void doesNotResendOnServiceUnavailable() {
        unavailableHits.set(0);
        ProxyResponse res = get(base + "/unavailable");

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getData()).isEqualTo(Map.of("message", "busy"));
        assertThat(unavailableHits.get()).isEqualTo(1);
    }

    @Test
    void followsSafeRedirect() {
        ProxyResponse res = get(base + "/safe-redirect");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getData()).isEqualTo(Map.of("ok", true, "path", "/api/reservations?from=redirect"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/to-metadata", "/to-metadata-decimal", "/to-link-local-v6"})
    void blocksRedirectToBlockedAddress(String path) {
        ProxyResponse res = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> get(base + path));

        assertThat(res.getStatus()).isEqualTo(502);
        assertThat(errorOf(res)).contains("blocked redirect");
    }

    @Test
    void blocksMetadataUrlDirectly() {
        ProxyResponse res = get("http://169.254.169.254/latest/meta-data/");

        assertThat(res.getStatus()).isEqualTo(502);
        assertThat(errorOf(res)).contains("link-local/metadata address not allowed");
    }

    /* ── 네트워크가 필요한 확인(-Dproxy.online=true) ── */

    @Test
    @EnabledIfSystemProperty(named = "proxy.online", matches = "true")
    void callsExternalHttpsApi() {
        ProxyResponse res = get("https://api.github.com/zen");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getData()).isInstanceOf(String.class);
    }

    // 실행 중인 서버가 없는 포트라 Nginx 가 502 를 준다. 프록시가 막지 않고 Nginx 까지 갔는지만 본다.
    @Test
    @EnabledIfSystemProperty(named = "proxy.online", matches = "true")
    void reachesWaivsPreviewDomain() {
        String url = "https://waivs.duckdns.org/preview/34567/api/reservations";
        assertThatCode(() -> SsrfGuard.validateTargetUrl(url)).doesNotThrowAnyException();

        ProxyResponse res = get(url);
        assertThat(res.getHeaders()).containsKey("Server");
        assertThat(String.valueOf(res.getHeaders().get("Server"))).contains("nginx");
    }
}
