package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_saved_test")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
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

    // params/headers/body를 JSON 문자열로 저장 (가장 단순/안전)
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String paramsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String headersJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String body;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}