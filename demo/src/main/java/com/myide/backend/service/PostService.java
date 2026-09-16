package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.post.Post;
import com.myide.backend.dto.PostDto;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.post.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;


    // ==========================================
    // 게시글 목록
    // ==========================================

    public Page<PostDto.ListResponse> getPosts(
            String keyword,
            String category,
            int page,
            int size
    ) {

        // 잘못된 페이지 값 방지
        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 20;
        }

        PageRequest pageable =
                PageRequest.of(
                        page,
                        size
                );

        Page<Post> posts =
                postRepository.searchBoard(
                        normalize(keyword),
                        normalize(category),
                        pageable
                );

        return posts.map(this::toListResponse);
    }


    // ==========================================
    // 게시글 상세
    // ==========================================

    @Transactional
    public PostDto.DetailResponse getPost(
            Long postId
    ) {

        Post post =
                findPost(postId);

        // 조회수 증가
        post.increaseViewCount();

        return toDetailResponse(post);
    }


    // ==========================================
    // 일반 게시글 작성
    // ==========================================

    @Transactional
    public PostDto.DetailResponse createPost(
            PostDto.CreateRequest request,
            Long currentUserId
    ) {

        // 로그인 확인
        validateLogin(currentUserId);

        // 입력값 확인
        validateCreateRequest(request);

        // 사용자 조회
        User user =
                userRepository.findById(currentUserId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "사용자를 찾을 수 없습니다."
                                )
                        );

        // 카테고리 정리
        String category =
                normalizeCategory(
                        request.getCategory()
                );

        // 일반 게시글 생성
        Post post =
                Post.createNormalPost(
                        user.getId(),
                        user.getNickname(),
                        request.getTitle().trim(),
                        request.getContent().trim(),
                        category
                );

        // 저장
        Post savedPost =
                postRepository.save(post);

        return toDetailResponse(savedPost);
    }


    // ==========================================
    // 일반 게시글 수정
    // ==========================================

    @Transactional
    public PostDto.DetailResponse updatePost(
            Long postId,
            PostDto.UpdateRequest request,
            Long currentUserId
    ) {

        // 로그인 확인
        validateLogin(currentUserId);

        // 입력값 확인
        validateUpdateRequest(request);

        // 게시글 조회
        Post post =
                findPost(postId);


        // ======================================
        // 공지는 일반 게시글 API에서 수정 불가
        // ======================================

        if (post.isNotice()) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "공지사항은 관리자만 수정할 수 있습니다."
            );
        }


        // ======================================
        // 작성자 본인 확인
        // ======================================

        if (!post.getAuthorId().equals(currentUserId)) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 게시글만 수정할 수 있습니다."
            );
        }


        // 게시글 수정
        post.update(
                request.getTitle().trim(),
                request.getContent().trim(),
                normalizeCategory(
                        request.getCategory()
                )
        );

        /*
         * @Transactional 상태이기 때문에
         * JPA Dirty Checking으로 DB에 자동 반영된다.
         */

        return toDetailResponse(post);
    }


    // ==========================================
    // 일반 게시글 삭제
    // ==========================================

    @Transactional
    public void deletePost(
            Long postId,
            Long currentUserId
    ) {

        // 로그인 확인
        validateLogin(currentUserId);

        // 게시글 조회
        Post post =
                findPost(postId);


        // ======================================
        // 일반 API에서 공지 삭제 방지
        // ======================================

        if (post.isNotice()) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "공지사항은 관리자만 삭제할 수 있습니다."
            );
        }


        // ======================================
        // 작성자 본인 확인
        // ======================================

        if (!post.getAuthorId().equals(currentUserId)) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 게시글만 삭제할 수 있습니다."
            );
        }


        postRepository.delete(post);
    }


    // ==========================================
    // Post 조회
    // ==========================================

    private Post findPost(
            Long postId
    ) {

        return postRepository.findById(postId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "게시글을 찾을 수 없습니다."
                        )
                );
    }


    // ==========================================
    // 로그인 검사
    // ==========================================

    private void validateLogin(
            Long currentUserId
    ) {

        if (currentUserId == null) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }
    }


    // ==========================================
    // 작성 요청 검사
    // ==========================================

    private void validateCreateRequest(
            PostDto.CreateRequest request
    ) {

        if (request == null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "게시글 정보를 입력해주세요."
            );
        }

        validateTitleAndContent(
                request.getTitle(),
                request.getContent()
        );
    }


    // ==========================================
    // 수정 요청 검사
    // ==========================================

    private void validateUpdateRequest(
            PostDto.UpdateRequest request
    ) {

        if (request == null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "수정할 정보를 입력해주세요."
            );
        }

        validateTitleAndContent(
                request.getTitle(),
                request.getContent()
        );
    }


    // ==========================================
    // 제목 / 내용 검사
    // ==========================================

    private void validateTitleAndContent(
            String title,
            String content
    ) {

        // 제목 없음
        if (title == null ||
                title.trim().isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "제목을 입력해주세요."
            );
        }

        // 제목 길이 초과
        if (title.trim().length() > 200) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "제목은 200자 이하로 입력해주세요."
            );
        }

        // 내용 없음
        if (content == null ||
                content.trim().isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "내용을 입력해주세요."
            );
        }
    }


    // ==========================================
    // 카테고리 기본값
    // ==========================================

    private String normalizeCategory(
            String category
    ) {

        if (category == null ||
                category.trim().isEmpty()) {

            return "GENERAL";
        }

        return category.trim();
    }


    // ==========================================
    // 검색 문자열 정리
    // ==========================================

    private String normalize(
            String value
    ) {

        if (value == null) {
            return null;
        }

        String result =
                value.trim();

        return result.isEmpty()
                ? null
                : result;
    }


    // ==========================================
    // 게시글 목록 DTO 변환
    // ==========================================

    private PostDto.ListResponse toListResponse(
            Post post
    ) {

        // 작성자 이름 조회
        String authorName =
                userRepository
                        .findById(
                                post.getAuthorId()
                        )
                        .map(User::getNickname)
                        .orElse("알 수 없음");


        // ======================================
        // 목록 미리보기 내용 생성
        // ======================================

        String contentSnippet = "";

        if (post.getContent() != null) {

            String content =
                    post.getContent().trim();

            if (content.length() > 100) {

                contentSnippet =
                        content.substring(
                                0,
                                100
                        ) + "...";

            } else {

                contentSnippet =
                        content;
            }
        }


        return PostDto.ListResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .contentSnippet(contentSnippet)
                .category(post.getCategory())

                // NORMAL / NOTICE
                .postType(post.getPostType())

                /*
                 * 현재 Post 엔티티에 태그 연관관계가 연결되지 않은
                 * 공지 추가용 구조이므로 빈 배열 반환.
                 *
                 * 기존 태그 Service를 다시 연결할 경우
                 * 이 부분만 실제 태그 목록으로 바꾸면 됨.
                 */
                .tags(List.of())

                .authorId(post.getAuthorId())
                .authorName(authorName)

                // DTO 필드 이름은 views
                .views((int) post.getViewCount())

                /*
                 * 현재 이 PostService 구조에는
                 * 좋아요 / 스크랩 Repository가 연결되어 있지 않으므로
                 * 우선 0 반환.
                 */
                .likeCount(0)
                .scrapCount(0)

                // 목록 썸네일
                .previewImageUrl(null)

                .createdAt(post.getCreatedAt())
                .build();
    }


    // ==========================================
    // 게시글 상세 DTO 변환
    // ==========================================

    private PostDto.DetailResponse toDetailResponse(
            Post post
    ) {

        // 작성자 이름 조회
        String authorName =
                userRepository
                        .findById(
                                post.getAuthorId()
                        )
                        .map(User::getNickname)
                        .orElse("알 수 없음");


        return PostDto.DetailResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .category(post.getCategory())

                // NORMAL / NOTICE
                .postType(post.getPostType())

                // 현재 태그 미연결
                .tags(List.of())

                .authorId(post.getAuthorId())
                .authorName(authorName)

                // DTO에서는 views
                .views((int) post.getViewCount())

                // 현재 좋아요/스크랩 Repository 미연결
                .likeCount(0)
                .scrapCount(0)

                /*
                 * 로그인 사용자별 좋아요/스크랩 상태는
                 * 현재 이 Service에서는 조회하지 않으므로 false.
                 */
                .liked(false)
                .scrapped(false)

                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())

                // 현재 첨부파일 미연결
                .attachments(List.of())

                .build();
    }
}