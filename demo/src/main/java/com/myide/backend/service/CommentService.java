package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.post.Comment;
import com.myide.backend.domain.post.Post;
import com.myide.backend.dto.PostDto;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.post.CommentRepository;
import com.myide.backend.repository.post.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;

    private final PostRepository postRepository;

    private final UserRepository userRepository;


    // ==========================================
    // 댓글 목록 조회
    //
    // GET /api/posts/{postId}/comments
    // ==========================================

    public Page<PostDto.CommentResponse> getComments(
            Long postId,
            int page,
            int size
    ) {

        // 게시글 존재 확인
        Post post =
                findPost(postId);


        // 공지에는 댓글 기능 사용하지 않음
        if (post.isNotice()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지사항에는 댓글을 사용할 수 없습니다."
            );
        }


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


        Page<Comment> comments =
                commentRepository
                        .findByPost_IdOrderByCreatedAtAsc(
                                postId,
                                pageable
                        );


        return comments.map(
                this::toResponse
        );
    }


    // ==========================================
    // 댓글 작성
    //
    // POST /api/posts/{postId}/comments
    // ==========================================

    @Transactional
    public PostDto.CommentResponse createComment(
            Long postId,
            PostDto.CommentRequest request,
            Long currentUserId
    ) {

        // 로그인 확인
        validateLogin(
                currentUserId
        );


        // 내용 확인
        validateRequest(
                request
        );


        // 게시글 조회
        Post post =
                findPost(
                        postId
                );


        // 공지 댓글 금지
        if (post.isNotice()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지사항에는 댓글을 작성할 수 없습니다."
            );
        }


        // 사용자 조회
        User user =
                userRepository
                        .findById(
                                currentUserId
                        )
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED,
                                        "사용자를 찾을 수 없습니다."
                                )
                        );


        // 댓글 생성
        Comment comment =
                Comment.create(
                        post,
                        request.getContent().trim(),
                        user.getId(),
                        user.getNickname()
                );


        // 저장
        Comment savedComment =
                commentRepository.save(
                        comment
                );


        return toResponse(
                savedComment
        );
    }


    // ==========================================
    // 댓글 수정
    //
    // PUT /api/posts/{postId}/comments/{commentId}
    // ==========================================

    @Transactional
    public PostDto.CommentResponse updateComment(
            Long postId,
            Long commentId,
            PostDto.CommentRequest request,
            Long currentUserId
    ) {

        validateLogin(
                currentUserId
        );


        validateRequest(
                request
        );


        // 게시글 존재 확인
        Post post =
                findPost(
                        postId
                );


        if (post.isNotice()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "공지사항에는 댓글을 사용할 수 없습니다."
            );
        }


        Comment comment =
                findComment(
                        commentId
                );


        // ======================================
        // 해당 게시글의 댓글인지 확인
        // ======================================

        if (
                comment.getPost() == null ||
                        !comment.getPost()
                                .getId()
                                .equals(postId)
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "해당 게시글의 댓글이 아닙니다."
            );
        }


        // ======================================
        // 작성자 확인
        // ======================================

        if (
                !comment.getAuthorId()
                        .equals(currentUserId)
        ) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 댓글만 수정할 수 있습니다."
            );
        }


        // 댓글 수정
        comment.updateContent(
                request.getContent().trim()
        );


        return toResponse(
                comment
        );
    }


    // ==========================================
    // 댓글 삭제
    //
    // DELETE /api/posts/{postId}/comments/{commentId}
    // ==========================================

    @Transactional
    public void deleteComment(
            Long postId,
            Long commentId,
            Long currentUserId
    ) {

        validateLogin(
                currentUserId
        );


        Comment comment =
                findComment(
                        commentId
                );


        // ======================================
        // 해당 게시글 댓글인지 확인
        // ======================================

        if (
                comment.getPost() == null ||
                        !comment.getPost()
                                .getId()
                                .equals(postId)
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "해당 게시글의 댓글이 아닙니다."
            );
        }


        // ======================================
        // 작성자 확인
        // ======================================

        if (
                !comment.getAuthorId()
                        .equals(currentUserId)
        ) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 댓글만 삭제할 수 있습니다."
            );
        }


        commentRepository.delete(
                comment
        );
    }


    // ==========================================
    // 게시글 조회
    // ==========================================

    private Post findPost(
            Long postId
    ) {

        return postRepository
                .findById(
                        postId
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "게시글을 찾을 수 없습니다."
                        )
                );
    }


    // ==========================================
    // 댓글 조회
    // ==========================================

    private Comment findComment(
            Long commentId
    ) {

        return commentRepository
                .findById(
                        commentId
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "댓글을 찾을 수 없습니다."
                        )
                );
    }


    // ==========================================
    // 로그인 확인
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
    // 댓글 입력값 확인
    // ==========================================

    private void validateRequest(
            PostDto.CommentRequest request
    ) {

        if (
                request == null ||
                        request.getContent() == null ||
                        request.getContent()
                                .trim()
                                .isEmpty()
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "댓글 내용을 입력해주세요."
            );
        }


        if (
                request.getContent()
                        .trim()
                        .length() > 2000
        ) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "댓글은 2000자 이하로 입력해주세요."
            );
        }
    }


    // ==========================================
    // DTO 변환
    // ==========================================

    private PostDto.CommentResponse toResponse(
            Comment comment
    ) {

        return PostDto.CommentResponse
                .builder()

                .id(
                        comment.getId()
                )

                .postId(
                        comment.getPost()
                                .getId()
                )

                .content(
                        comment.getContent()
                )

                .authorId(
                        comment.getAuthorId()
                )

                .authorName(
                        comment.getAuthorName()
                )

                .createdAt(
                        comment.getCreatedAt()
                )

                .updatedAt(
                        comment.getUpdatedAt()
                )

                .build();
    }
}