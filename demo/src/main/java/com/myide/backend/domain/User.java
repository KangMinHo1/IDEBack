package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 💡 무분별한 객체 생성 방지
@AllArgsConstructor
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 회원 고유 번호

    // 로그인 아이디 (이메일 형태)
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    // 비밀번호
    @Column(nullable = false, length = 255)
    private String password;

    // 사용자 닉네임
    @Column(nullable = false, unique = true, length = 30) // 💡 닉네임 중복 방지 추가
    private String nickname;

    // 프로필 이미지 경로 (서버 로컬 경로 또는 URL 저장)
    @Column(length = 500)
    private String profileImageUrl;

    // 가입일
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // 안전한 정보 수정 메서드
    public void updateProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }
}