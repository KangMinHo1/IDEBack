package com.myide.backend.domain.post;

import com.myide.backend.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "posts", indexes = {
        // 💡 [성능 최적화] 카테고리 필터링과 최신순 정렬이 매우 빈번하므로 복합 인덱스 생성
        @Index(name = "idx_post_category_created", columnList = "category, created_at DESC")
})
public class Post extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Lob // 내용이 길 수 있으므로 TEXT 타입으로 저장
    @Column(nullable = false)
    private String content;

    @Column(nullable = false, length = 50)
    private String category;

    // 작성자 정보 (실제 User 엔티티와 연관관계를 맺어도 되지만, 빠른 조회를 위해 식별자와 이름만 저장)
    @Column(nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 50)
    private String authorName;

    // 💡 [성능 최적화] 매번 COUNT 쿼리를 날리지 않기 위한 반정규화 컬럼들
    @Column(nullable = false)
    private int views = 0;

    @Column(nullable = false)
    private int likeCount = 0;

    @Column(nullable = false)
    private int scrapCount = 0;

    // 💡 [성능 최적화] 태그는 단순 문자열이므로 별도 테이블 조인 대신 컬렉션 사용
    // @BatchSize로 N+1 쿼리를 방어하여 여러 게시글의 태그를 한방에 가져옵니다.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "tag_name")
    @BatchSize(size = 100)
    private List<String> tags = new ArrayList<>();

    // 첨부파일 양방향 매핑
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<Attachment> attachments = new ArrayList<>();

    @Builder
    public Post(String title, String content, String category, Long authorId, String authorName, List<String> tags) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.authorId = authorId;
        this.authorName = authorName;
        if (tags != null) this.tags = tags;
    }

    // --- 비즈니스 로직 (Setter 대신 도메인 주도 설계) ---

    public void update(String title, String content, String category, List<String> tags) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.tags.clear();
        if (tags != null) this.tags.addAll(tags);
    }

    // 💡 [추가된 부분] 좋아요 증감 로직 (마이너스 방지 방어코드 포함)
    public void increaseLikeCount() {
        this.likeCount++;
    }

    public void decreaseLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    // 💡 [추가된 부분] 스크랩 증감 로직 (마이너스 방지 방어코드 포함)
    public void increaseScrapCount() {
        this.scrapCount++;
    }

    public void decreaseScrapCount() {
        if (this.scrapCount > 0) {
            this.scrapCount--;
        }
    }
}