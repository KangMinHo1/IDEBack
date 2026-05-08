package com.myide.backend.repository.post;

import com.myide.backend.domain.post.PostScrap;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScrapRepository extends JpaRepository<PostScrap, Long> {

    // 이 유저가 이 글을 스크랩 했는가? (빠른 확인용)
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 스크랩 취소 기능
    void deleteByPostIdAndUserId(Long postId, Long userId);

    void deleteByPostId(Long postId);
}
