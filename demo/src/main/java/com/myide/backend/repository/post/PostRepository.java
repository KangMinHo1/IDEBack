package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Post;
import com.myide.backend.domain.post.PostType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    // ==========================================
    // 사용자 게시판 조회
    //
    // - 공지사항 우선 표시
    // - 제목 + 내용 검색
    // - 카테고리 필터
    // - 공지는 카테고리와 관계없이 표시
    // ==========================================

    @Query("""
            SELECT p
            FROM Post p
            WHERE
                (
                    :keyword IS NULL
                    OR :keyword = ''
                    OR LOWER(p.title)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(p.content)
                        LIKE LOWER(CONCAT('%', :keyword, '%'))
                )
                AND
                (
                    p.postType = com.myide.backend.domain.post.PostType.NOTICE
                    OR :category IS NULL
                    OR :category = ''
                    OR :category = 'ALL'
                    OR p.category = :category
                )
            ORDER BY
                CASE
                    WHEN p.postType = com.myide.backend.domain.post.PostType.NOTICE
                    THEN 0
                    ELSE 1
                END,
                p.createdAt DESC
            """)
    Page<Post> searchBoard(
            @Param("keyword") String keyword,
            @Param("category") String category,
            Pageable pageable
    );


    // ==========================================
    // 관리자 공지 목록
    // ==========================================

    Page<Post> findByPostType(
            PostType postType,
            Pageable pageable
    );


    // ==========================================
    // 특정 사용자 작성 게시글 수
    // ==========================================

    long countByAuthorId(Long authorId);
}