package com.myide.backend.repository.post;

import com.myide.backend.domain.post.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LikeRepository extends JpaRepository<PostLike, Long> {

    // 특정 유저가 특정 게시글에 좋아요를 눌렀는지 확인
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 좋아요 취소
    void deleteByPostIdAndUserId(Long postId, Long userId);

    // 게시글 삭제 시 좋아요 전체 삭제
    void deleteByPostId(Long postId);

    // 특정 게시글의 실제 좋아요 수
    long countByPost_Id(Long postId);

    // ==========================================
    // 마이페이지 - 내가 좋아요한 게시글
    //
    // post를 JOIN FETCH해서 목록 변환 시
    // LazyInitializationException / N+1을 피한다.
    // ==========================================

    @Query("""
            SELECT pl
            FROM PostLike pl
            JOIN FETCH pl.post p
            WHERE pl.userId = :userId
            ORDER BY pl.createdAt DESC
            """)
    List<PostLike> findMyLikesOrderByCreatedAtDesc(
            @Param("userId") Long userId
    );
}
