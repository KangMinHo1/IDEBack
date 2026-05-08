package com.myide.backend.domain.post;

import com.myide.backend.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "comments", indexes = {
        // 게시글 상세 조회 시 댓글을 시간순으로 빠르게 가져오기 위한 인덱스
        @Index(name = "idx_comment_post_created", columnList = "post_id, created_at ASC")
})
public class Comment extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private Long authorId;

    @Column(nullable = false)
    private String authorName;

    @Builder
    public Comment(Post post, String content, Long authorId, String authorName) {
        this.post = post;
        this.content = content;
        this.authorId = authorId;
        this.authorName = authorName;
    }
}
