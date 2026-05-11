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

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, unique = true, length = 30)
    private String nickname;

    @Column(length = 500)
    private String profileImageUrl;

    //  깃허브 Access Token을 저장할 컬럼
    @Column(length = 255)
    private String githubAccessToken;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public void updateProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    public void updatePassword(String newPassword) {
        this.password = newPassword;
    }

    public void updateEmail(String email) {
        this.email = email;
    }

    //  깃허브 토큰 저장용 메서드
    public void updateGithubAccessToken(String token) {
        this.githubAccessToken = token;
    }
}