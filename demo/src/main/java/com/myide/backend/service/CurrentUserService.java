package com.myide.backend.service;

import com.myide.backend.exception.ApiException;
import com.myide.backend.security.JwtProvider;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final JwtProvider jwtProvider;

    public Long getCurrentUserId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attrs == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "인증 정보가 없습니다.");
        }

        String authorization = attrs.getRequest().getHeader("Authorization");

        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authorization 헤더가 필요합니다.");
        }

        if (!authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Bearer 토큰 형식이 올바르지 않습니다.");
        }

        String token = authorization.substring(7).trim();

        if (token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "토큰이 비어 있습니다.");
        }

        try {
            if (!jwtProvider.validateToken(token)) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다.");
            }

            Long userId = jwtProvider.getUserIdFromToken(token);

            if (userId == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "토큰에서 사용자 정보를 찾을 수 없습니다.");
            }

            return userId;
        } catch (ApiException e) {
            throw e;
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다.");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "토큰 처리 중 오류가 발생했습니다.");
        }
    }
}