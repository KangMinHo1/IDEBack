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
        name = "posts",
        indexes = {
                @Index(
                        name = "idx_post_author",
                        columnList = "author_id"
                ),
                @Index(
                        name = "idx_post_type",
                        columnList = "post_type"
                ),
                @Index(
                        name = "idx_post_created",
                        columnList = "created_at"
                )
        }
)
public class Post extends BaseTimeEntity {

    // ==========================================
    // ID
    // ==========================================

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


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
    //
    // 기존 DB posts.author_name 대응
    // ==========================================

    @Column(
            name = "author_name",
            nullable = false,
            length = 50
    )
    private String authorName;


    // ==========================================
    // 제목
    // ==========================================

    @Column(
            nullable = false,
            length = 200
    )
    private String title;


    // ==========================================
    // 내용
    //
    // @Lob 사용하지 않음
    // LOWER(content) Hibernate 오류 방지
    // ==========================================

    @Column(
            nullable = false,
            columnDefinition = "LONGTEXT"
    )
    private String content;


    // ==========================================
    // 카테고리
    // ==========================================

    @Column(
            nullable = false,
            length = 50
    )
    private String category;


    // ==========================================
    // 게시글 종류
    //
    // NORMAL
    // NOTICE
    // ==========================================

    @Enumerated(EnumType.STRING)
    @Column(
            name = "post_type",
            nullable = false,
            length = 20
    )
    private PostType postType;


    // ==========================================
    // 조회수
    // ==========================================

    @Column(
            name = "view_count",
            nullable = false
    )
    private int viewCount;


    // ==========================================
    // 좋아요 수
    //
    // 기존 DB posts.like_count 대응
    // ==========================================

    @Column(
            name = "like_count",
            nullable = false
    )
    private int likeCount;


    // ==========================================
    // 스크랩 수
    //
    // 기존 DB posts.scrap_count 대응
    // ==========================================

    @Column(
            name = "scrap_count",
            nullable = false
    )
    private int scrapCount;


    // ==========================================
    // 생성자
    // ==========================================

    @Builder
    private Post(
            Long authorId,
            String authorName,
            String title,
            String content,
            String category,
            PostType postType,
            int viewCount,
            int likeCount,
            int scrapCount
    ) {

        this.authorId =
                authorId;

        this.authorName =
                authorName;

        this.title =
                title;

        this.content =
                content;

        this.category =
                category;

        this.postType =
                postType == null
                        ? PostType.NORMAL
                        : postType;

        this.viewCount =
                viewCount;

        this.likeCount =
                likeCount;

        this.scrapCount =
                scrapCount;
    }


    // ==========================================
    // 일반 게시글 생성
    // ==========================================

    public static Post createNormalPost(
            Long authorId,
            String authorName,
            String title,
            String content,
            String category
    ) {

        return Post.builder()
                .authorId(authorId)
                .authorName(authorName)
                .title(title)
                .content(content)
                .category(category)
                .postType(PostType.NORMAL)

                // 신규 게시글 기본값
                .viewCount(0)
                .likeCount(0)
                .scrapCount(0)

                .build();
    }


    // ==========================================
    // 관리자 공지 생성
    // ==========================================

    public static Post createNotice(
            Long adminId,
            String adminName,
            String title,
            String content
    ) {

        return Post.builder()
                .authorId(adminId)
                .authorName(adminName)
                .title(title)
                .content(content)

                // 공지 카테고리
                .category("NOTICE")

                // 공지 타입
                .postType(PostType.NOTICE)

                // 기본값
                .viewCount(0)
                .likeCount(0)
                .scrapCount(0)

                .build();
    }


    // ==========================================
    // 일반 게시글 수정
    // ==========================================

    public void update(
            String title,
            String content,
            String category
    ) {

        this.title =
                title;

        this.content =
                content;

        this.category =
                category;
    }


    // ==========================================
    // 공지 수정
    // ==========================================

    public void updateNotice(
            String title,
            String content
    ) {

        this.title =
                title;

        this.content =
                content;
    }


    // ==========================================
    // 조회수 증가
    // ==========================================

    public void increaseViewCount() {

        this.viewCount++;
    }


    // ==========================================
    // 좋아요 증가
    // ==========================================

    public void increaseLikeCount() {

        this.likeCount++;
    }


    // ==========================================
    // 좋아요 감소
    // ==========================================

    public void decreaseLikeCount() {

        if (this.likeCount > 0) {

            this.likeCount--;
        }
    }


    // ==========================================
    // 스크랩 증가
    // ==========================================

    public void increaseScrapCount() {

        this.scrapCount++;
    }


    // ==========================================
    // 스크랩 감소
    // ==========================================

    public void decreaseScrapCount() {

        if (this.scrapCount > 0) {

            this.scrapCount--;
        }
    }


    // ==========================================
    // 공지 여부
    // ==========================================

    public boolean isNotice() {

        return this.postType ==
                PostType.NOTICE;
    }
}