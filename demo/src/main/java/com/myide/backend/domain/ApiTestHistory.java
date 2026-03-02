package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_test_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ApiTestHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 10)
    private String method;

    @Column(length = 2048)
    private String url;

    private Integer status;
    private Boolean success;
    private Long durationMs;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}