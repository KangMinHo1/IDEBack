package com.myide.backend.service;

import com.myide.backend.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class CurrentUserService {

    public String getCurrentUserId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attrs == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증 정보가 없습니다.");
        }

        String userId = attrs.getRequest().getHeader("X-USER-ID");
        if (userId == null || userId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "X-USER-ID 헤더가 필요합니다.");
        }

        return userId;
    }
}