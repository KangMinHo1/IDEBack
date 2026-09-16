package com.myide.backend.controller;

import com.myide.backend.dto.PostDto;
import com.myide.backend.service.AdminNoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final AdminNoticeService adminNoticeService;


    // ==========================================
    // 관리자 공지 목록 조회
    //
    // GET /api/admin/notices
    //
    // 예:
    // GET /api/admin/notices?page=0&size=20
    // ==========================================

    @GetMapping
    public ResponseEntity<Page<PostDto.ListResponse>> getNotices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Long currentUserId
    ) {

        Page<PostDto.ListResponse> response =
                adminNoticeService.getNotices(
                        page,
                        size,
                        currentUserId
                );

        return ResponseEntity.ok(response);
    }


    // ==========================================
    // 관리자 공지 상세 조회
    //
    // GET /api/admin/notices/{postId}
    // ==========================================

    @GetMapping("/{postId}")
    public ResponseEntity<PostDto.DetailResponse> getNotice(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long currentUserId
    ) {

        PostDto.DetailResponse response =
                adminNoticeService.getNotice(
                        postId,
                        currentUserId
                );

        return ResponseEntity.ok(response);
    }


    // ==========================================
    // 관리자 공지 작성
    //
    // POST /api/admin/notices
    //
    // {
    //     "title": "서비스 점검 안내",
    //     "content": "서비스 점검이 진행됩니다."
    // }
    // ==========================================

    @PostMapping
    public ResponseEntity<PostDto.DetailResponse> createNotice(
            @RequestBody PostDto.NoticeCreateRequest request,
            @AuthenticationPrincipal Long currentUserId
    ) {

        PostDto.DetailResponse response =
                adminNoticeService.createNotice(
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(response);
    }


    // ==========================================
    // 관리자 공지 수정
    //
    // PUT /api/admin/notices/{postId}
    //
    // {
    //     "title": "수정된 공지 제목",
    //     "content": "수정된 공지 내용"
    // }
    // ==========================================

    @PutMapping("/{postId}")
    public ResponseEntity<PostDto.DetailResponse> updateNotice(
            @PathVariable Long postId,
            @RequestBody PostDto.NoticeUpdateRequest request,
            @AuthenticationPrincipal Long currentUserId
    ) {

        PostDto.DetailResponse response =
                adminNoticeService.updateNotice(
                        postId,
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(response);
    }


    // ==========================================
    // 관리자 공지 삭제
    //
    // DELETE /api/admin/notices/{postId}
    // ==========================================

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deleteNotice(
            @PathVariable Long postId,
            @AuthenticationPrincipal Long currentUserId
    ) {

        adminNoticeService.deleteNotice(
                postId,
                currentUserId
        );

        return ResponseEntity
                .noContent()
                .build();
    }
}