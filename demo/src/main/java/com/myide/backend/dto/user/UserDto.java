package com.myide.backend.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

public class UserDto {

    // 회원 생성
    @Getter
    @NoArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        private String email;

        @NotBlank(message = "비밀번호는 필수입니다.")
        private String password;

        @NotBlank(message = "닉네임은 필수입니다.")
        private String nickname;
    }

    //회원 수정
    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {
        @NotBlank(message = "닉네임은 필수입니다.")
        private String nickname;
        private String profileImageUrl;
    }
    // 회원 조회
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private String email;
        private String nickname;
        private String profileImageUrl;
        private LocalDateTime createdAt;
    }
}