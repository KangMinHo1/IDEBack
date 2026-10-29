package com.myide.backend.domain.post;

import com.myide.backend.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * comment_likes 테이블 정의용 엔티티.
 * 읽기/쓰기는 CommunityInteractionService 가 JdbcTemplate 으로 하고, 이 클래스는 ddl-auto 로 테이블을 만들기 위해 둡니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "comment_likes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_comment_like_user", columnNames = {"comment_id", "user_id"})
        }
)
public class CommentLike extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
