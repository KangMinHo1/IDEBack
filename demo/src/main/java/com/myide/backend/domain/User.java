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

    @Column(length = 1000)
    private String githubAccessToken;

    /**
     * GitHub API에서 author 필터로 사용할 GitHub login 값.
     * 예: https://github.com/abc 에서 abc
     */
    @Column(length = 100)
    private String githubUsername;

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

    public void updateGithubInfo(String token, String githubUsername) {
        this.githubAccessToken = token;
        this.githubUsername = githubUsername;
    }

    public void clearGithubInfo() {
        this.githubAccessToken = null;
        this.githubUsername = null;
    }
}