package com.myide.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    // JWT 토큰을 검사하는 커스텀 인증 필터
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 비밀번호 암호화에 사용할 PasswordEncoder 등록
     *
     * 회원가입 시 비밀번호를 BCrypt 방식으로 암호화하고,
     * 로그인 시 입력한 비밀번호와 DB에 저장된 암호화 비밀번호를 비교할 때 사용한다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Spring Security의 전체 보안 필터 체인 설정
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                /*
                 * CSRF 보호 비활성화
                 *
                 * 현재 백엔드는 JWT 기반 REST API 구조이므로
                 * 서버 세션을 사용하지 않는다.
                 * 따라서 일반적인 폼 로그인 방식에서 필요한 CSRF 보호는 비활성화한다.
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /*
                 * CORS 설정 적용
                 *
                 * 프론트엔드와 백엔드의 주소가 다를 때 발생하는
                 * Cross-Origin 요청을 허용하기 위한 설정이다.
                 */
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                /*
                 * 세션 사용 안 함
                 *
                 * JWT 인증 방식은 서버에 로그인 세션을 저장하지 않고,
                 * 요청마다 Authorization 헤더의 토큰을 검사하는 방식이다.
                 */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                /*
                 * URL별 접근 권한 설정
                 */
                .authorizeHttpRequests(auth -> auth

                        /*
                         * CORS preflight 요청 허용
                         *
                         * 브라우저는 실제 요청 전에 OPTIONS 요청을 먼저 보내
                         * 해당 API 호출이 허용되는지 확인할 수 있다.
                         * 이 요청이 막히면 실제 GET/POST 요청도 실행되지 않는다.
                         */
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        /*
                         * 인증 없이 접근 가능한 API
                         *
                         * 로그인, 회원가입, 토큰 재발급, 로그아웃은
                         * 아직 JWT 토큰이 없거나 토큰 갱신이 필요한 상황에서도 호출되어야 한다.
                         *
                         * WebSocket 연결, favicon, .well-known 경로도
                         * 보안 필터에서 막히지 않도록 허용한다.
                         */
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/ws/**",
                                "/favicon.ico",
                                "/.well-known/**"
                        ).permitAll()

                        /*
                         * GitHub 관련 API는 인증 필요
                         *
                         * GitHub 연동 상태, 저장소 목록, 토큰 정보 등은
                         * 로그인한 사용자 기준으로 관리되어야 하므로
                         * JWT 인증을 통과한 사용자만 접근할 수 있게 한다.
                         */
                        .requestMatchers("/api/github/**").authenticated()

                        /*
                         * 개발 중 임시 전체 허용
                         *
                         * 현재는 개발 편의를 위해 나머지 API를 모두 허용한다.
                         * 최종 단계 또는 실제 서비스에서는 아래 설정을
                         * authenticated()로 바꾸는 것이 안전하다.
                         *
                         * 예:
                         * .anyRequest().authenticated()
                         */
                        .anyRequest().permitAll()
                )

                /*
                 * JWT 인증 필터 등록
                 *
                 * Spring Security의 기본 로그인 인증 필터보다 먼저 실행되도록 설정한다.
                 * 요청이 컨트롤러에 도달하기 전에 JWT 토큰을 검사하고,
                 * 유효한 경우 SecurityContext에 사용자 인증 정보를 저장한다.
                 */
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    /**
     * CORS 허용 정책 설정
     *
     * 프론트엔드 개발 서버에서 백엔드 API를 호출할 수 있도록
     * 허용할 출처, HTTP 메서드, 요청 헤더, 응답 헤더를 지정한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        /*
         * 요청을 허용할 프론트엔드 주소
         *
         * localhost:3000은 Next.js 개발 서버,
         * localhost:5173은 Vite 개발 서버에서 주로 사용된다.
         *
         * 127.0.0.1 주소도 함께 허용하여
         * localhost 대신 IP로 접속했을 때도 CORS 오류가 나지 않게 한다.
         */
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:3000",
                "http://127.0.0.1:3000",
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        ));

        /*
         * 허용할 HTTP 메서드
         *
         * OPTIONS는 CORS preflight 요청에 필요하다.
         */
        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));

        /*
         * 프론트엔드에서 보낼 수 있는 요청 헤더
         *
         * Authorization은 JWT 토큰 전달에 필요하고,
         * Content-Type은 JSON 요청 body 전송에 필요하다.
         */
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "X-Requested-With"
        ));

        /*
         * 프론트엔드에서 읽을 수 있도록 노출할 응답 헤더
         *
         * 기본적으로 브라우저는 일부 응답 헤더만 접근할 수 있다.
         * Authorization 헤더에 새 토큰을 내려주는 구조라면 노출 설정이 필요하다.
         */
        configuration.setExposedHeaders(List.of(
                "Authorization",
                "Set-Cookie"
        ));

        /*
         * 쿠키/인증 정보를 포함한 요청 허용
         *
         * credentials 옵션을 사용하는 요청을 허용한다.
         * 단, allowCredentials(true)를 사용할 때는
         * 허용 origin을 "*"로 두면 안 된다.
         */
        configuration.setAllowCredentials(true);

        /*
         * preflight 요청 결과를 브라우저가 캐싱할 시간
         *
         * 3600초 = 1시간
         */
        configuration.setMaxAge(3600L);

        /*
         * 위에서 만든 CORS 설정을 모든 API 경로에 적용
         */
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}