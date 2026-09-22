package com.myide.backend.repository.message;

import com.myide.backend.domain.post.Comment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectMessageCommentRepository
        extends JpaRepository<Comment, Long> {


    boolean existsByPost_IdAndAuthorId(
            Long postId,
            Long authorId
    );
}