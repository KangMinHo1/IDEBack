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
    private final CommunityInteractionService interactions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Long viewerId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }
    public Page<PostDto.ListResponse> getPosts(
            String keyword,
            String category,
            int page,
            int size
    ) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 20;
        }
        size = Math.min(size, 100);
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
    @Transactional
    public PostDto.DetailResponse getPost(
            Long postId
    ) {
        Post post =
                findPost(postId);
        post.increaseViewCount();
        return toDetailResponse(post);
    }
    @Transactional
    public PostDto.DetailResponse createPost(
            PostDto.CreateRequest request,
            Long currentUserId
    ) {
        validateLogin(currentUserId);
        validateCreateRequest(request);
        User user =
                userRepository.findById(currentUserId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "사용자를 찾을 수 없습니다."
                                )
                        );
        String category =
                normalizeCategory(
                        request.getCategory()
                );
        Post post =
                Post.createNormalPost(
                        user.getId(),
                        user.getNickname(),
                        request.getTitle().trim(),
                        request.getContent().trim(),
                        category
                );
        Post savedPost =
                postRepository.save(post);
        return toDetailResponse(savedPost);
    }
    @Transactional
    public PostDto.DetailResponse updatePost(
            Long postId,
            PostDto.UpdateRequest request,
            Long currentUserId
    ) {
        validateLogin(currentUserId);
        validateUpdateRequest(request);
        Post post =
                findPost(postId);
        if (post.isNotice()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "공지사항은 관리자만 수정할 수 있습니다."
            );
        }
        if (!post.getAuthorId().equals(currentUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 게시글만 수정할 수 있습니다."
            );
        }
        post.update(
                request.getTitle().trim(),
                request.getContent().trim(),
                normalizeCategory(
                        request.getCategory()
                )
        );
        return toDetailResponse(post);
    }
    @Transactional
    public void deletePost(
            Long postId,
            Long currentUserId
    ) {
        validateLogin(currentUserId);
        Post post =
                findPost(postId);
        if (post.isNotice()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "공지사항은 관리자만 삭제할 수 있습니다."
            );
        }
        if (!post.getAuthorId().equals(currentUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 게시글만 삭제할 수 있습니다."
            );
        }
        jdbc.queryForObject("select id from posts where id=? for update", Long.class, postId);
        jdbc.update("delete from post_reports where post_id=?", postId);
        jdbc.update("delete from post_likes where post_id=?", postId);
        jdbc.update("delete from post_scraps where post_id=?", postId);
        jdbc.update("delete from comments where post_id=?", postId);
        postRepository.delete(post);
    }
    private Post findPost(
            Long postId
    ) {
        // 같은 게시글의 조회/수정과 좋아요 집계 갱신을 직렬화합니다.
        var ids = jdbc.query("select id from posts where id=? for update", (rs,n) -> rs.getLong(1), postId);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        return postRepository.findById(postId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "게시글을 찾을 수 없습니다."
                        )
                );
    }
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
    private void validateTitleAndContent(
            String title,
            String content
    ) {
        if (title == null ||
                title.trim().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "제목을 입력해주세요."
            );
        }
        if (title.trim().length() > 200) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "제목은 200자 이하로 입력해주세요."
            );
        }
        if (content == null ||
                content.trim().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "내용을 입력해주세요."
            );
        }
    }
    private String normalizeCategory(
            String category
    ) {
        if (category == null ||
                category.trim().isEmpty()) {
            return "GENERAL";
        }
        return category.trim();
    }
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
    private PostDto.ListResponse toListResponse(
            Post post
    ) {
        String authorName =
                userRepository
                        .findById(
                                post.getAuthorId()
                        )
                        .map(User::getNickname)
                        .orElse("알 수 없음");
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
        var state = interactions.state(post.getId(), viewerId());
        return PostDto.ListResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .contentSnippet(contentSnippet)
                .category(post.getCategory())
                .postType(post.getPostType())
                .tags(List.of())
                .authorId(post.getAuthorId())
                .authorName(authorName)
                .views((int) post.getViewCount())
                .likeCount(Math.toIntExact(state.likeCount()))
                .scrapCount(Math.toIntExact(state.scrapCount()))
                .previewImageUrl(null)
                .createdAt(post.getCreatedAt())
                .build();
    }
    private PostDto.DetailResponse toDetailResponse(
            Post post
    ) {
        String authorName =
                userRepository
                        .findById(
                                post.getAuthorId()
                        )
                        .map(User::getNickname)
                        .orElse("알 수 없음");
        var state = interactions.state(post.getId(), viewerId());
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
                .likeCount(Math.toIntExact(state.likeCount()))
                .scrapCount(Math.toIntExact(state.scrapCount()))
                .liked(state.liked())
                .scrapped(state.scrapped())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .attachments(List.of())
                .build();
    }
}