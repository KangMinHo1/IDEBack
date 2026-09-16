package com.myide.backend.dto.auth;

import com.myide.backend.domain.User;
import com.myide.backend.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

public class AuthDto {

    // ==========================================
    // 로그인 요청
    // ==========================================

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {

        @Email
        @NotBlank
        private String email;

        @NotBlank
        private String password;
    }


    // ==========================================
    // 로그인 사용자 정보
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummary {

        private Long id;

        private String email;

        private String nickname;

        private String profileImageUrl;

        // USER / ADMIN
        private UserRole role;


        public static UserSummary from(User user) {

            return UserSummary.builder()
                    .id(user.getId())
                    .email(user.getEmail())
                    .nickname(user.getNickname())
                    .profileImageUrl(user.getProfileImageUrl())
                    .role(user.getRole())
                    .build();
        }
    }


    // ==========================================
    // 토큰 응답
    // ==========================================

    /*
     * 프론트 호환성을 위해 token 필드도 같이 내려줍니다.
     * 신규 프론트에서는 accessToken을 사용합니다.
     */

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenResponse {

        private String accessToken;

        private String token;

        private Long userId;

        private UserSummary user;


        public static TokenResponse of(
                String accessToken,
                User user
        ) {

            return TokenResponse.builder()
                    .accessToken(accessToken)
                    .token(accessToken)
                    .userId(user.getId())
                    .user(UserSummary.from(user))
                    .build();
        }
    }


    // ==========================================
    // 서버 내부 토큰 발급 결과
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenIssueResult {

        private String accessToken;

        private String refreshToken;

        private User user;


        public TokenResponse toResponse() {

            return TokenResponse.of(
                    accessToken,
                    user
            );
        }
    }
}