package com.myide.backend.repository.post;

import com.myide.backend.domain.post.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LikeRepository extends JpaRepository<PostLike, Long> {

    // 💡 [현업 스킬] countBy.. 대신 existsBy.. 사용하기!
    // 특정 유저가 좋아요를 눌렀는지 확인할 때, count()는 테이블 끝까지 뒤져서 개수를 세지만
    // exists()는 딱 1개라도 발견되면 그 즉시 검색을 멈추고 true를 반환하므로 성능이 훨씬 빠릅니다.
    boolean existsByPostIdAndUserId(Long postId, Long userId);

    // 좋아요 취소 기능 (게시글 ID와 유저 ID가 일치하는 기록 삭제)
    void deleteByPostIdAndUserId(Long postId, Long userId);

    void deleteByPostId(Long postId);
}
