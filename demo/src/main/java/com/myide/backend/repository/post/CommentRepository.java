package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    // 💡 특정 게시글(postId)에 달린 댓글들을 시간순(오래된 순)으로 페이징해서 가져옵니다.
    // 댓글이 수천 개 달릴 수 있으므로 List가 아닌 Page 객체로 쪼개서 반환합니다.
    Page<Comment> findByPostIdOrderByCreatedAtAsc(Long postId, Pageable pageable);
    void deleteByPostId(Long postId);
}