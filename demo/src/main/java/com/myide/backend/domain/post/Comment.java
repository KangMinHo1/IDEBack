package com.myide.backend.domain.post;

import com.myide.backend.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "comments",
        indexes = {
                @Index(
                        name = "idx_comment_post_created",
                        columnList = "post_id, created_at ASC"
                )
        }
)
public class Comment extends BaseTimeEntity {

    // ==========================================
    // 댓글 ID
    // ==========================================

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    // ==========================================
    // 게시글
    //
    // comments.post_id
    // ==========================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "post_id",
            nullable = false
    )
    private Post post;


    // ==========================================
    // 댓글 내용
    //
    // DB content = longtext
    // ==========================================

    @Column(
            name = "content",
            nullable = false,
            columnDefinition = "LONGTEXT"
    )
    private String content;


    // ==========================================
    // 작성자 ID
    // ==========================================

    @Column(
            name = "author_id",
            nullable = false
    )
    private Long authorId;


    // ==========================================
    // 작성자 이름
    // ==========================================

    @Column(
            name = "author_name",
            nullable = false,
            length = 50
    )
    private String authorName;


    // ==========================================
    // 생성자
    // ==========================================

    @Builder
    private Comment(
            Post post,
            String content,
            Long authorId,
            String authorName
    ) {

        this.post = post;
        this.content = content;
        this.authorId = authorId;
        this.authorName = authorName;
    }


    // ==========================================
    // 댓글 생성
    // ==========================================

    public static Comment create(
            Post post,
            String content,
            Long authorId,
            String authorName
    ) {

        return Comment.builder()
                .post(post)
                .content(content)
                .authorId(authorId)
                .authorName(authorName)
                .build();
    }


    // ==========================================
    // 댓글 수정
    // ==========================================

    public void updateContent(
            String content
    ) {

        this.content = content;
    }
}