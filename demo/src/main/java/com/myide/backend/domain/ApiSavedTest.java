package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_saved_test")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiSavedTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(length = 10)
    private String method;

    @Column(length = 2048)
    private String url;

    // Query Params
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String paramsJson;

    // Request Headers
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String headersJson;

    // raw body
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String body;

    // none | raw | form-data | x-www-form-urlencoded
    @Column(length = 32)
    private String bodyMode;

    // json | text | html | xml
    @Column(length = 16)
    private String rawType;

    // x-www-form-urlencoded 항목
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String urlEncodedJson;

    // none | bearer | basic | api-key
    @Column(length = 20)
    private String authType;

    /*
     * 졸업작품/개발도구에서는 복원을 위해 저장합니다.
     * 운영 서비스에서는 토큰/비밀번호/API Key를 평문 저장하지 말고
     * 암호화 또는 Secret Store를 사용해야 합니다.
     */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String bearerToken;

    private String basicUsername;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String basicPassword;

    private String apiKeyName;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String apiKeyValue;

    // header | query
    @Column(length = 16)
    private String apiKeyLocation;

    // API assertion 목록
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String assertionsJson;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.bodyMode == null || this.bodyMode.isBlank()) {
            this.bodyMode = "none";
        }

        if (this.rawType == null || this.rawType.isBlank()) {
            this.rawType = "json";
        }

        if (this.authType == null || this.authType.isBlank()) {
            this.authType = "none";
        }

        if (this.apiKeyLocation == null || this.apiKeyLocation.isBlank()) {
            this.apiKeyLocation = "header";
        }
    }
}
