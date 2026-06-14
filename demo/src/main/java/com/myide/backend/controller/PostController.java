package com.myide.backend.controller;


import com.myide.backend.domain.User;
import com.myide.backend.dto.PostDto;

import com.myide.backend.repository.UserRepository;
import com.myide.backend.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.myide.backend.domain.notification.NotificationType;
import com.myide.backend.domain.post.Post;
import com.myide.backend.repository.post.PostRepository;
import com.myide.backend.service.NotificationService;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final UserRepository userRepository; // 💡 유저 정보를 조회하기 위해 주입
    private final NotificationService notificationService;
    private final PostRepository postRepository;

    // ==========================================
    // 1. 게시글 목록 조회
    // ==========================================
    @GetMapping
    public ResponseEntity<Page<PostDto.ListResponse>> getPosts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 10) Pageable pageable) {

        Page<PostDto.ListResponse> response = postService.getPosts(category, keyword, pageable);
        return ResponseEntity.ok(response);
    }

    // ==========================================
    // 2. 게시글 상세 조회 (IP 기반 조회수 방어)
    // ==========================================
    @GetMapping("/{postId}")
    public ResponseEntity<PostDto.DetailResponse> getPostDetail(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long currentUserId,
            HttpServletRequest request) {

        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty() || "unknown".equalsIgnoreCase(clientIp)) {
            clientIp = request.getRemoteAddr();
        }

        PostDto.DetailResponse detailResponse = postService.getPostDetail(postId, currentUserId, clientIp);
        return ResponseEntity.ok(detailResponse);
    }

    // ==========================================
    // 3. 게시글 작성 (실제 유저 이름 적용)
    // ==========================================
    @PostMapping
    public ResponseEntity<Long> createPost(
            @RequestBody PostDto.CreateRequest request,
            @AuthenticationPrincipal Long currentUserId) {

        // 💡 DB에서 현재 로그인한 유저의 진짜 정보를 가져옵니다.
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 💡 닉네임이 있으면 닉네임, 없으면 이메일을 작성자 이름으로 사용합니다.
        String authorName = (user.getNickname() != null && !user.getNickname().isEmpty())
                ? user.getNickname()
                : user.getEmail();

        Long newPostId = postService.createPost(request, currentUserId, authorName);

        notificationService.notifyAllUsersExcept(
                currentUserId,
                NotificationType.BOARD_POST,
                "게시판 알림",
                authorName + "님이 새 게시글을 작성했습니다: " + request.getTitle(),
                "/community/" + newPostId
        );

        return ResponseEntity.ok(newPostId);
    }

    // ==========================================
    // 4. 게시글 수정
    // ==========================================
    @PutMapping("/{postId}")
    public ResponseEntity<Void> updatePost(
            @PathVariable Long postId,
            @RequestBody PostDto.UpdateRequest request,
            @AuthenticationPrincipal Long currentUserId) {

        postService.updatePost(postId, request, currentUserId);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // 5. 게시글 삭제
    // ==========================================
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long currentUserId) {

        postService.deletePost(postId, currentUserId);
        return ResponseEntity.ok().build();
    }

    // ==========================================
    // 6. 좋아요 토글
    // ==========================================
    @PostMapping("/{postId}/like")
    public ResponseEntity<PostDto.InteractionResponse> toggleLike(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long currentUserId) {
        return ResponseEntity.ok(postService.toggleLike(postId, currentUserId));
    }

    // ==========================================
    // 7. 스크랩 토글
    // ==========================================
    @PostMapping("/{postId}/scrap")
    public ResponseEntity<PostDto.InteractionResponse> toggleScrap(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long currentUserId) {
        return ResponseEntity.ok(postService.toggleScrap(postId, currentUserId));
    }

    // ==========================================
    // 8. 댓글 목록 조회
    // ==========================================
    @GetMapping("/{postId}/comments")
    public ResponseEntity<Page<PostDto.CommentResponse>> getComments(
            @PathVariable Long postId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(postService.getComments(postId, pageable));
    }

    // ==========================================
    // 9. 댓글 작성 (실제 유저 이름 적용)
    // ==========================================
    @PostMapping("/{postId}/comments")
    public ResponseEntity<PostDto.CommentResponse> createComment(
            @PathVariable Long postId,
            @RequestBody PostDto.CommentRequest request,
            @AuthenticationPrincipal Long currentUserId) {

        // 💡 댓글 작성자도 동일하게 DB에서 실제 이름을 조회합니다.
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        String authorName = (user.getNickname() != null && !user.getNickname().isEmpty())
                ? user.getNickname()
                : user.getEmail();

        PostDto.CommentResponse response =
                postService.createComment(postId, request, currentUserId, authorName);

        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시글을 찾을 수 없습니다."));

        if (!post.getAuthorId().equals(currentUserId)) {
            notificationService.notifyUser(
                    post.getAuthorId(),
                    null,
                    NotificationType.BOARD_COMMENT,
                    "댓글 알림",
                    authorName + "님이 내 게시글에 댓글을 남겼습니다.",
                    "/community/" + postId
            );
        }

        return ResponseEntity.ok(response);
    }
}