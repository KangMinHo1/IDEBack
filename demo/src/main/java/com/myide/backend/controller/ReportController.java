package com.myide.backend.controller;

import com.myide.backend.dto.ReportDto;
import com.myide.backend.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;


    // ==========================================
    // 게시글 신고
    //
    // POST /api/posts/{postId}/reports
    // ==========================================

    @PostMapping("/{postId}/reports")
    public ResponseEntity<ReportDto.Response> reportPost(
            @PathVariable Long postId,
            @RequestBody ReportDto.CreateRequest request,
            @AuthenticationPrincipal Long currentUserId
    ) {

        ReportDto.Response response =
                reportService.reportPost(
                        postId,
                        request,
                        currentUserId
                );

        return ResponseEntity.ok(response);
    }
}