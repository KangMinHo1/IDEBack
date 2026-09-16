package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.UserRole;
import com.myide.backend.domain.post.Post;
import com.myide.backend.domain.post.PostType;
import com.myide.backend.dto.PostDto;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.post.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNoticeService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;


    // ==========================================
    // 관리자 공지 목록 조회
    // ==========================================

    public Page<PostDto.ListResponse> getNotices(
            int page,
            int size,
            Long currentUserId
    ) {

        validateAdmin(currentUserId);

        if (page < 0) {
            page = 0;
        }

        if (size <= 0) {
            size = 20;
        }

        PageRequest pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.DESC,
                                "createdAt"
                        )
                );

        return postRepository
                .findByPostType(
                        PostType.NOTICE,
                        pageable
                )
                .map(this::toListResponse);
    }


    // ==========================================
    // 관리자 공지 상세 조회
    // ==========================================

    public PostDto.DetailResponse getNotice(
            Long postId,
            Long currentUserId
    ) {

        validateAdmin(currentUserId);

        Post notice =
                findNotice(postId);

        return toDetailResponse(notice);
    }


    // ==========================================
    // 관리자 공지 작성
    // ==========================================

    @Transactional
    public PostDto.DetailResponse createNotice(
            PostDto.NoticeCreateRequest request,
            Long currentUserId
    ) {

        // 관리자 조회
        User admin =
                validateAdmin(currentUserId);

        // 입력값 확인
        validateNoticeRequest(
                request == null
                        ? null
                        : request.getTitle(),

                request == null
                        ? null
                        : request.getContent()
        );

        /*
         * Post.createNotice 파라미터
         *
         * 1. 관리자 ID
         * 2. 관리자 이름
         * 3. 제목
         * 4. 내용
         */
        Post notice =
                Post.createNotice(
                        admin.getId(),
                        admin.getNickname(),
                        request.getTitle().trim(),
                        request.getContent().trim()
                );

        Post savedNotice =
                postRepository.save(notice);

        return toDetailResponse(savedNotice);
    }


    // ==========================================
    // 관리자 공지 수정
    // ==========================================

    @Transactional
    public PostDto.DetailResponse updateNotice(
            Long postId,
            PostDto.NoticeUpdateRequest request,
            Long currentUserId
    ) {

        validateAdmin(currentUserId);

        validateNoticeRequest(
                request == null
                        ? null
                        : request.getTitle(),

                request == null
                        ? null
                        : request.getContent()
        );

        Post notice =
                findNotice(postId);

        notice.updateNotice(
                request.getTitle().trim(),
                request.getContent().trim()
        );

        // JPA Dirty Checking으로 자동 UPDATE

        return toDetailResponse(notice);
    }


    // ==========================================
    // 관리자 공지 삭제
    // ==========================================

    @Transactional
    public void deleteNotice(
            Long postId,
            Long currentUserId
    ) {

        validateAdmin(currentUserId);

        Post notice =
                findNotice(postId);

        postRepository.delete(notice);
    }


    // ==========================================
    // 관리자 권한 확인
    // ==========================================

    private User validateAdmin(
            Long currentUserId
    ) {

        if (currentUserId == null) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }

        User user =
                userRepository.findById(currentUserId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED,
                                        "사용자를 찾을 수 없습니다."
                                )
                        );

        if (user.getRole() != UserRole.ADMIN) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "관리자만 사용할 수 있습니다."
            );
        }

        return user;
    }


    // ==========================================
    // 공지 조회
    // ==========================================

    private Post findNotice(
            Long postId
    ) {

        Post post =
                postRepository.findById(postId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "공지사항을 찾을 수 없습니다."
                                )
                        );

        if (!post.isNotice()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지사항이 아닙니다."
            );
        }

        return post;
    }


    // ==========================================
    // 공지 입력값 검사
    // ==========================================

    private void validateNoticeRequest(
            String title,
            String content
    ) {

        if (title == null ||
                title.trim().isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지 제목을 입력해주세요."
            );
        }

        if (title.trim().length() > 200) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지 제목은 200자 이하로 입력해주세요."
            );
        }

        if (content == null ||
                content.trim().isEmpty()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지 내용을 입력해주세요."
            );
        }
    }


    // ==========================================
    // 목록 DTO 변환
    // ==========================================

    private PostDto.ListResponse toListResponse(
            Post post
    ) {

        String authorName =
                post.getAuthorName() != null
                        ? post.getAuthorName()
                        : getAuthorName(post.getAuthorId());

        return PostDto.ListResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .contentSnippet(
                        createContentSnippet(
                                post.getContent()
                        )
                )
                .category(post.getCategory())
                .postType(post.getPostType())
                .tags(List.of())
                .authorId(post.getAuthorId())
                .authorName(authorName)
                .views((int) post.getViewCount())
                .likeCount(0)
                .scrapCount(0)
                .previewImageUrl(null)
                .createdAt(post.getCreatedAt())
                .build();
    }


    // ==========================================
    // 상세 DTO 변환
    // ==========================================

    private PostDto.DetailResponse toDetailResponse(
            Post post
    ) {

        String authorName =
                post.getAuthorName() != null
                        ? post.getAuthorName()
                        : getAuthorName(post.getAuthorId());

        return PostDto.DetailResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .category(post.getCategory())
                .postType(post.getPostType())
                .tags(List.of())
                .authorId(post.getAuthorId())
                .authorName(authorName)
                .views((int) post.getViewCount())
                .likeCount(0)
                .scrapCount(0)
                .liked(false)
                .scrapped(false)
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .attachments(List.of())
                .build();
    }


    // ==========================================
    // 작성자 이름 조회
    // ==========================================

    private String getAuthorName(
            Long authorId
    ) {

        if (authorId == null) {
            return "관리자";
        }

        return userRepository
                .findById(authorId)
                .map(User::getNickname)
                .orElse("관리자");
    }


    // ==========================================
    // 목록용 내용 미리보기
    // ==========================================

    private String createContentSnippet(
            String content
    ) {

        if (content == null ||
                content.trim().isEmpty()) {

            return "";
        }

        String trimmedContent =
                content.trim();

        int maxLength = 100;

        if (trimmedContent.length() <= maxLength) {
            return trimmedContent;
        }

        return trimmedContent.substring(
                0,
                maxLength
        ) + "...";
    }
}