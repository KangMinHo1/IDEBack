package com.myide.backend.dto;

import com.myide.backend.domain.post.PostType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class PostDto {


    // =========================================================
    // Request
    // =========================================================


    // ==========================================
    // 일반 게시글 작성
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class CreateRequest {

        private String title;

        private String content;

        private String category;

        private List<String> tags;

        private List<AttachmentRequest> attachments;
    }


    // ==========================================
    // 일반 게시글 수정
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {

        private String title;

        private String content;

        private String category;

        private List<String> tags;

        private List<AttachmentRequest> attachments;
    }


    // ==========================================
    // 관리자 공지 작성
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class NoticeCreateRequest {

        private String title;

        private String content;
    }


    // ==========================================
    // 관리자 공지 수정
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class NoticeUpdateRequest {

        private String title;

        private String content;
    }


    // ==========================================
    // 첨부파일 요청
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class AttachmentRequest {

        private String name;

        // image / file
        private String type;

        private String url;
    }


    // ==========================================
    // 댓글 작성 / 수정
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class CommentRequest {

        private String content;
    }


    // =========================================================
    // Response
    // =========================================================


    // ==========================================
    // 게시글 목록 응답
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListResponse {

        private Long id;

        private String title;

        // 목록에서 보여줄 내용 일부
        private String contentSnippet;

        private String category;


        // ======================================
        // NORMAL : 일반 게시글
        // NOTICE : 관리자 공지
        // ======================================

        private PostType postType;


        // ======================================
        // 태그
        // ======================================

        private List<String> tags;


        // ======================================
        // 작성자
        // ======================================

        private Long authorId;

        private String authorName;


        // ======================================
        // 통계
        // ======================================

        private int views;

        private int likeCount;

        private int scrapCount;


        // ======================================
        // 목록 썸네일
        // ======================================

        private String previewImageUrl;


        // ======================================
        // 날짜
        // ======================================

        private LocalDateTime createdAt;
    }


    // ==========================================
    // 게시글 상세 응답
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailResponse {

        private Long id;

        private String title;

        private String content;

        private String category;


        // ======================================
        // NORMAL / NOTICE
        // ======================================

        private PostType postType;


        // ======================================
        // 태그
        // ======================================

        private List<String> tags;


        // ======================================
        // 작성자
        // ======================================

        private Long authorId;

        private String authorName;


        // ======================================
        // 통계
        // ======================================

        private int views;

        private int likeCount;

        private int scrapCount;


        // ======================================
        // 현재 로그인 사용자 상태
        // ======================================

        private boolean liked;

        private boolean scrapped;


        // ======================================
        // 날짜
        // ======================================

        private LocalDateTime createdAt;

        private LocalDateTime updatedAt;


        // ======================================
        // 첨부파일
        // ======================================

        private List<AttachmentResponse> attachments;
    }


    // ==========================================
    // 첨부파일 응답
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentResponse {

        private Long id;

        private String name;

        private String type;

        private String url;
    }


    // ==========================================
    // 댓글 응답
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentResponse {

        private Long id;


        // 어떤 게시글의 댓글인지
        private Long postId;


        // 댓글 내용
        private String content;


        // 작성자 ID
        private Long authorId;


        // 작성자 닉네임
        private String authorName;


        // 작성일
        private LocalDateTime createdAt;


        // 수정일
        private LocalDateTime updatedAt;
    }


    // ==========================================
    // 좋아요 / 스크랩 토글 응답
    // ==========================================

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractionResponse {

        /*
         * true
         * 좋아요/스크랩 활성화
         *
         * false
         * 좋아요/스크랩 취소
         */
        private boolean active;


        // 변경 후 전체 개수
        private int count;
    }
}