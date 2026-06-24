package com.myide.backend.service.apitest;

import java.net.InetAddress;
import java.net.URI;

public class SsrfGuard {

    // 시연/개발용 설정
    // true면 localhost, 127.0.0.1, 192.168.x.x, 10.x.x.x, 172.16~31.x.x 허용
    private static final boolean ALLOW_PRIVATE_NETWORK_FOR_DEMO = true;

    public static void validateTargetUrl(String url) {
        try {
            URI uri = URI.create(url);

            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (scheme == null || host == null || host.isBlank()) {
                throw new IllegalArgumentException("invalid url");
            }

            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("only http/https allowed");
            }

            InetAddress address = InetAddress.getByName(host);

            // 0.0.0.0 같은 주소는 허용하지 않음
            if (address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("any local address not allowed");
            }

            // localhost, 127.x.x.x 허용
            if (address.isLoopbackAddress()) {
                if (ALLOW_PRIVATE_NETWORK_FOR_DEMO) {
                    return;
                }

                throw new IllegalArgumentException("private/internal address not allowed");
            }

            // 192.168.x.x, 10.x.x.x, 172.16~31.x.x 허용
            if (address.isSiteLocalAddress()) {
                if (ALLOW_PRIVATE_NETWORK_FOR_DEMO) {
                    return;
                }

                throw new IllegalArgumentException("private/internal address not allowed");
            }

        } catch (Exception e) {
            throw new IllegalArgumentException("invalid target url: " + e.getMessage(), e);
        }
    }
}