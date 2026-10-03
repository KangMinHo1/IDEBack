package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository
        extends JpaRepository<Comment, Long> {

    // ==========================================
    // 특정 게시글 댓글 조회
    //
    // Comment.post.id 기준
    // 오래된 댓글 → 최신 댓글 순
    // ==========================================

    Page<Comment> findByPost_IdOrderByCreatedAtAsc(
            Long postId,
            Pageable pageable
    );

    // ==========================================
    // 특정 게시글 댓글 전체 삭제
    // ==========================================

    void deleteByPost_Id(
            Long postId
    );

    // ==========================================
    // 특정 게시글 댓글 수
    // ==========================================

    long countByPost_Id(Long postId);

    // ==========================================
    // 마이페이지 - 내가 쓴 댓글
    //
    // post를 JOIN FETCH해서 postTitle 조회 시
    // 추가 쿼리를 줄인다.
    // ==========================================

    @Query("""
            SELECT c
            FROM Comment c
            JOIN FETCH c.post p
            WHERE c.authorId = :authorId
            ORDER BY c.createdAt DESC
            """)
    List<Comment> findMyCommentsOrderByCreatedAtDesc(
            @Param("authorId") Long authorId
    );
}
