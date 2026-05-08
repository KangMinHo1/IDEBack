package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PostRepositoryCustom {
    // 카테고리, 검색어, 페이징 정보를 받아 조건에 맞는 게시글 목록을 반환
    Page<Post> searchPosts(String category, String keyword, Pageable pageable);
}
