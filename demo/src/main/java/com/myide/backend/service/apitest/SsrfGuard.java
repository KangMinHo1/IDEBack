package com.myide.backend.service.apitest;

import java.net.InetAddress;
import java.net.URI;

public class SsrfGuard {

    public static void validateTargetUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();

            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("invalid host");
            }

            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
                return;
            }

            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress()) {
                throw new IllegalArgumentException("private/internal address not allowed");
            }

        } catch (Exception e) {
            throw new IllegalArgumentException("invalid target url: " + e.getMessage(), e);
        }
    }
}