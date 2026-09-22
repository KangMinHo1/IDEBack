package com.myide.backend.repository.message;

import com.myide.backend.domain.post.Post;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectMessagePostRepository
        extends JpaRepository<Post, Long> {
}