package com.myide.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 프로젝트 최상단 경로를 가져옵니다.
        String uploadPath = System.getProperty("user.dir") + "/uploads/";

        // 경로가 OS에 맞게 올바르게 인식되도록 file:/// 접두사를 붙여줍니다.
        String resourceLocation = "file:///" + uploadPath.replace("\\", "/");

        // 💡 브라우저에서 /uploads/** 로 요청이 오면 실제 폴더에서 찾아줍니다.
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(resourceLocation);
    }
}