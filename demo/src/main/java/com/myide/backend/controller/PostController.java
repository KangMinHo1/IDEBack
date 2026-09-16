package com.myide.backend.controller;

import com.myide.backend.dto.PostDto;
import com.myide.backend.service.CommentService;
import com.myide.backend.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    private final CommentService commentService;


    // ==========================================
    // 게시글 목록 조회
    //
    // GET /api/posts
    // ==========================================

    @GetMapping
    public ResponseEntity<Page<PostDto.ListResponse>> getPosts(

            @RequestParam(required = false)
            String keyword,

            @RequestParam(required = false)
            String category,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size

    ) {

        Page<PostDto.ListResponse> response =
                postService.getPosts(
                        keyword,
                        category,
                        page,
                        size
                );

        return ResponseEntity.ok(
                response
        );
    }


    // ==========================================
    // 게시글 상세 조회
    //
    // GET /api/posts/{postId}
    // ==========================================

    @GetMapping("/{postId}")
    public ResponseEntity<PostDto.DetailResponse> getPost(

            @PathVariable
            Long postId

    ) {

        PostDto.DetailResponse response =
                postService.getPost(
                        postId
                );

        return ResponseEntity.ok(
                response
        );
    }


    // ==========================================
    // 일반 게시글 작성
    //
    // POST /api/posts
    // ==========================================

    @PostMapping
    public ResponseEntity<PostDto.DetailResponse> createPost(

            @RequestBody
            PostDto.CreateRequest request,

            @AuthenticationPrincipal
            Long currentUserId

    ) {

        PostDto.DetailResponse response =
                postService.createPost(
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(
                response
        );
    }


    // ==========================================
    // 일반 게시글 수정
    //
    // PUT /api/posts/{postId}
    // ==========================================

    @PutMapping("/{postId}")
    public ResponseEntity<PostDto.DetailResponse> updatePost(

            @PathVariable
            Long postId,

            @RequestBody
            PostDto.UpdateRequest request,

            @AuthenticationPrincipal
            Long currentUserId

    ) {

        PostDto.DetailResponse response =
                postService.updatePost(
                        postId,
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(
                response
        );
    }


    // ==========================================
    // 일반 게시글 삭제
    //
    // DELETE /api/posts/{postId}
    // ==========================================

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(

            @PathVariable
            Long postId,

            @AuthenticationPrincipal
            Long currentUserId

    ) {

        postService.deletePost(
                postId,
                currentUserId
        );

        return ResponseEntity
                .noContent()
                .build();
    }


    // =========================================================
    // 댓글 목록 조회
    //
    // GET /api/posts/{postId}/comments
    //
    // 예:
    // GET /api/posts/3/comments?page=0&size=20
    // =========================================================

    @GetMapping("/{postId}/comments")
    public ResponseEntity<Page<PostDto.CommentResponse>> getComments(

            @PathVariable
            Long postId,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size

    ) {

        Page<PostDto.CommentResponse> response =
                commentService.getComments(
                        postId,
                        page,
                        size
                );

        return ResponseEntity.ok(
                response
        );
    }


    // =========================================================
    // 댓글 작성
    //
    // POST /api/posts/{postId}/comments
    //
    // body:
    //
    // {
    //   "content": "댓글 내용"
    // }
    // =========================================================

    @PostMapping("/{postId}/comments")
    public ResponseEntity<PostDto.CommentResponse> createComment(

            @PathVariable
            Long postId,

            @RequestBody
            PostDto.CommentRequest request,

            @AuthenticationPrincipal
            Long currentUserId

    ) {

        PostDto.CommentResponse response =
                commentService.createComment(
                        postId,
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(
                response
        );
    }


    // =========================================================
    // 댓글 수정
    //
    // PUT /api/posts/{postId}/comments/{commentId}
    // =========================================================

    @PutMapping("/{postId}/comments/{commentId}")
    public ResponseEntity<PostDto.CommentResponse> updateComment(

            @PathVariable
            Long postId,

            @PathVariable
            Long commentId,

            @RequestBody
            PostDto.CommentRequest request,

            @AuthenticationPrincipal
            Long currentUserId

    ) {

        PostDto.CommentResponse response =
                commentService.updateComment(
                        postId,
                        commentId,
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(
                response
        );
    }


    // =========================================================
    // 댓글 삭제
    //
    // DELETE /api/posts/{postId}/comments/{commentId}
    // =========================================================

    @DeleteMapping("/{postId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(

            @PathVariable
            Long postId,

            @PathVariable
            Long commentId,

            @AuthenticationPrincipal
            Long currentUserId

    ) {

        commentService.deleteComment(
                postId,
                commentId,
                currentUserId
        );

        return ResponseEntity
                .noContent()
                .build();
    }
}