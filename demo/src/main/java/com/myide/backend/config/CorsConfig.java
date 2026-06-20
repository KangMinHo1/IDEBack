package com.myide.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",

                        // 집/학교/공유기에서 자주 쓰는 사설 IP 대역
                        "http://192.168.*.*:*",
                        "http://10.*.*.*:*",
                        "http://172.16.*.*:*",
                        "http://172.17.*.*:*",
                        "http://172.18.*.*:*",
                        "http://172.19.*.*:*",
                        "http://172.20.*.*:*",
                        "http://172.21.*.*:*",
                        "http://172.22.*.*:*",
                        "http://172.23.*.*:*",
                        "http://172.24.*.*:*",
                        "http://172.25.*.*:*",
                        "http://172.26.*.*:*",
                        "http://172.27.*.*:*",
                        "http://172.28.*.*:*",
                        "http://172.29.*.*:*",
                        "http://172.30.*.*:*",
                        "http://172.31.*.*:*"
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}