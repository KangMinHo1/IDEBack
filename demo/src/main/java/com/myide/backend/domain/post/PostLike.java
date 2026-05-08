package com.myide.backend.domain.post;

import com.myide.backend.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 기본 생성자 접근 제어 (JPA 스펙)
@Table(name = "post_likes",
        // 💡 [현업 스킬] 복합 유니크 제약조건 (Unique Constraint)
        // 한 명의 유저(user_id)는 하나의 게시글(post_id)에 딱 1번만 좋아요를 누를 수 있도록 DB 엔진이 강제로 막아줍니다. (따닥 클릭 버그 방지)
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_post_like_user", columnNames = {"post_id", "user_id"})
        }
)
public class PostLike extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 💡 [성능 최적화] Post 객체를 통째로 가져오면 무거우니, 진짜 쓸 때만 가져오도록 지연 로딩(LAZY) 설정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(name = "user_id", nullable = false)
    private Long userId; // 유저 정보는 MSA 환경이나 성능을 고려해 ID만 저장합니다.

    @Builder
    public PostLike(Post post, Long userId) {
        this.post = post;
        this.userId = userId;
    }
}