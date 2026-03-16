package com.myide.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 헤더에서 토큰 꺼내기
        String jwt = resolveToken(request);

        // 2. 토큰이 존재하고 유효하다면
        if (StringUtils.hasText(jwt) && jwtProvider.validateToken(jwt)) {
            // 3. 토큰에서 유저 ID를 추출
            Long userId = jwtProvider.getUserIdFromToken(jwt);

            // 4. 권한(Role)은 없으므로 빈 리스트를 넣고, 유저 ID를 Principal(주체)로 설정하여 스프링 시큐리티에 저장!
            // 💡 이렇게 하면 나중에 컨트롤러에서 @AuthenticationPrincipal Long userId 로 바로 ID를 꺼내 쓸 수 있습니다.
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    // 헤더에서 "Bearer " 부분을 떼어내고 진짜 토큰값만 가져오는 헬퍼 메서드
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}