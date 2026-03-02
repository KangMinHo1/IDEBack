package com.myide.backend.service;

import java.net.URI;

public class SsrfGuard {

    public static void validateTargetUrl(String raw) {
        try {
            URI uri = URI.create(raw);

            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only http/https allowed");
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Invalid host");

            // ✅ 로컬/내부로 향하는 요청 최소 차단 (필요하면 더 강화)
            String h = host.toLowerCase();
            if (h.equals("localhost") || h.equals("127.0.0.1")) {
                throw new IllegalArgumentException("Localhost is blocked");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid url: " + e.getMessage());
        }
    }
}