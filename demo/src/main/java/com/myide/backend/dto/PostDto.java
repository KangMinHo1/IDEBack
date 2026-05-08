package com.myide.backend.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class PostDto {

    // ==========================================
    // [Request] 프론트엔드 -> 백엔드로 데이터가 들어올 때
    // ==========================================

    @Getter
    @NoArgsConstructor
    public static class CreateRequest {
        private String title;
        private String content;
        private String category;
        private List<String> tags;
        private List<AttachmentRequest> attachments; // 생성 시 넘어오는 첨부파일 정보
    }

    @Getter
    @NoArgsConstructor
    public static class UpdateRequest {
        private String title;
        private String content;
        private String category;
        private List<String> tags;
        private List<AttachmentRequest> attachments; // 수정 시 유지/추가된 첨부파일
    }

    @Getter
    @NoArgsConstructor
    public static class AttachmentRequest {
        private String name;
        private String type; // "image" or "file"
        private String url;
    }

    @Getter
    @NoArgsConstructor
    public static class CommentRequest {
        private String content;
    }


    // ==========================================
    // [Response] 백엔드 -> 프론트엔드로 데이터가 나갈 때
    // ==========================================

    @Getter
    @Builder
    public static class ListResponse {
        private Long id;
        private String title;
        private String contentSnippet; // 목록용 미리보기 내용
        private String category;
        private List<String> tags;
        private Long authorId;
        private String authorName;
        private int views;
        private int likeCount;
        private int scrapCount;
        private String previewImageUrl; // 💡 썸네일 이미지 (첨부파일 중 첫 번째 이미지)
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class DetailResponse {
        private Long id;
        private String title;
        private String content;
        private String category;
        private List<String> tags;
        private Long authorId;
        private String authorName;
        private int views;
        private int likeCount;
        private int scrapCount;

        // 💡 로그인한 유저가 이 글을 좋아요/스크랩 했는지 여부 (상태 표시용)
        private boolean liked;
        private boolean scrapped;

        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<AttachmentResponse> attachments;
    }

    @Getter
    @Builder
    public static class AttachmentResponse {
        private Long id;
        private String name;
        private String type;
        private String url;
    }

    @Getter
    @Builder
    public static class CommentResponse {
        private Long id;
        private Long postId;
        private String content;
        private Long authorId;
        private String authorName;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // 좋아요, 스크랩 토글 시 반환할 상태 결과값
    @Getter
    @Builder
    public static class InteractionResponse {
        private boolean active; // 현재 상태 (true: 좋아요 됨, false: 취소됨)
        private int count;      // 변경된 총 개수
    }
}