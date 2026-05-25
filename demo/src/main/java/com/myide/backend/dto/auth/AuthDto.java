package com.myide.backend.dto.auth;

import com.myide.backend.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

public class AuthDto {

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

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummary {
        private Long id;
        private String email;
        private String nickname;
        private String profileImageUrl;

        public static UserSummary from(User user) {
            return UserSummary.builder()
                    .id(user.getId())
                    .email(user.getEmail())
                    .nickname(user.getNickname())
                    .profileImageUrl(user.getProfileImageUrl())
                    .build();
        }
    }

    /*
     * 프론트 호환성을 위해 token 필드도 같이 내려줍니다.
     * 신규 프론트에서는 accessToken을 사용하세요.
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

        public static TokenResponse of(String accessToken, User user) {
            return TokenResponse.builder()
                    .accessToken(accessToken)
                    .token(accessToken)
                    .userId(user.getId())
                    .user(UserSummary.from(user))
                    .build();
        }
    }

    /*
     * 서버 내부에서만 사용하는 발급 결과.
     * refreshToken은 HttpOnly Cookie로 내려가며 JSON body에는 포함하지 않습니다.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenIssueResult {
        private String accessToken;
        private String refreshToken;
        private User user;

        public TokenResponse toResponse() {
            return TokenResponse.of(accessToken, user);
        }
    }
}