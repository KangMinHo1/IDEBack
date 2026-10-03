package com.myide.backend.repository.post;

import com.myide.backend.domain.post.PostScrap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScrapRepository extends JpaRepository<PostScrap, Long> {

    // 이 유저가 이 글을 스크랩 했는가?
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 스크랩 취소
    void deleteByPostIdAndUserId(Long postId, Long userId);

    // 게시글 삭제 시 스크랩 전체 삭제
    void deleteByPostId(Long postId);

    // ==========================================
    // 마이페이지 - 내가 스크랩한 게시글
    // ==========================================

    @Query("""
            SELECT ps
            FROM PostScrap ps
            JOIN FETCH ps.post p
            WHERE ps.userId = :userId
            ORDER BY ps.createdAt DESC
            """)
    List<PostScrap> findMyScrapsOrderByCreatedAtDesc(
            @Param("userId") Long userId
    );
}
