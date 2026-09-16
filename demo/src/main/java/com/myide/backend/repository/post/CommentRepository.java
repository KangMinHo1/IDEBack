package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}