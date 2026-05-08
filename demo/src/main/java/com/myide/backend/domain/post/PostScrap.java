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
@Table(name = "post_scraps",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_post_scrap_user", columnNames = {"post_id", "user_id"})
        }
)
public class PostScrap extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Builder
    public PostScrap(Post post, Long userId) {
        this.post = post;
        this.userId = userId;
    }
}