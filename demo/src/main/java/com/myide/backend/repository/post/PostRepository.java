package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long>, PostRepositoryCustom {

    // 💡 [동시성 문제 해결의 핵심] DB 차원에서 직접 업데이트
    @Modifying(clearAutomatically = true) // 이 쿼리 실행 후 영속성 컨텍스트(1차 캐시)를 비워줌
    @Query("update Post p set p.views = p.views + 1 where p.id = :id")
    void incrementViews(@Param("id") Long id);
}
