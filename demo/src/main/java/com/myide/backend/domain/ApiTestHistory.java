package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_test_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
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

    @Column(length = 100)
    private String statusText;

    private Boolean success;

    private Long durationMs;

    private Long responseSize;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String responseBody;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String responseHeadersJson;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.success == null && this.status != null) {
            this.success = this.status >= 200 && this.status < 400;
        }
    }
}
