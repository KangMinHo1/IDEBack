package com.myide.backend.repository.post;

import com.myide.backend.domain.post.Post;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.myide.backend.domain.post.QPost.post;

@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Post> searchPosts(String category, String keyword, Pageable pageable) {

        // 1. 데이터(게시글) 조회 쿼리
        List<Post> content = queryFactory
                .selectFrom(post) // SELECT * FROM posts
                .where(
                        categoryEq(category), // 카테고리 조건 (없으면 무시됨)
                        titleOrContentContains(keyword) // 검색어 조건 (없으면 무시됨)
                )
                .orderBy(post.createdAt.desc()) // 최신순 정렬
                .offset(pageable.getOffset()) // 페이지 시작 지점 (예: 1페이지면 0번부터)
                .limit(pageable.getPageSize()) // 한 번에 가져올 개수 (예: 10개)
                .fetch(); // 쿼리 실행!

        // 2. 전체 개수(Count) 조회 쿼리 (페이징 처리를 위해 반드시 필요)
        JPAQuery<Long> countQuery = queryFactory
                .select(post.count())
                .from(post)
                .where(
                        categoryEq(category),
                        titleOrContentContains(keyword)
                );

        // 3. 스프링의 유틸리티를 사용해 최적화된 Page 객체 생성
        // (만약 조회된 데이터가 페이지 크기보다 작으면 카운트 쿼리를 실행하지 않음 = 성능 최적화)
        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // 💡 [핵심] 동적 쿼리를 위한 BooleanExpression 메서드들
    private BooleanExpression categoryEq(String category) {
        // category가 null이거나 비어있으면 null을 반환하여 조건에서 아예 제외시킴!
        return StringUtils.hasText(category) ? post.category.eq(category) : null;
    }

    private BooleanExpression titleOrContentContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null; // 검색어가 없으면 통과!
        }
        // 제목이나 내용 중에 검색어가 포함(LIKE '%검색어%')되어 있으면 찾음
        return post.title.contains(keyword).or(post.content.contains(keyword));
    }
}
