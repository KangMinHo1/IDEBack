package com.myide.backend.domain.post;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "attachments")
public class Attachment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 다대일(N:1) 양방향 관계. 지연로딩(LAZY)을 걸어 불필요한 쿼리를 막습니다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 20)
    private String type; // "image" | "file"

    @Column(nullable = false, length = 1000)
    private String url;

    @Builder
    public Attachment(Post post, String name, String type, String url) {
        this.post = post;
        this.name = name;
        this.type = type;
        this.url = url;
    }
}
