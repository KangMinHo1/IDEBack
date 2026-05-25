package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, unique = true, length = 30)
    private String nickname;

    @Column(length = 500)
    private String profileImageUrl;

    /*
     * GitHub OAuth access token 저장 컬럼.
     * 길이를 넉넉하게 잡아 토큰 길이 변화나 확장에 대비합니다.
     */
    @Column(length = 1000)
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

    public void updateGithubAccessToken(String token) {
        this.githubAccessToken = token;
    }
}