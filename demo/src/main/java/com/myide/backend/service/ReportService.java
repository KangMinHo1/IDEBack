package com.myide.backend.service;

import com.myide.backend.domain.post.Post;
import com.myide.backend.domain.report.Report;
import com.myide.backend.dto.ReportDto;
import com.myide.backend.repository.post.PostRepository;
import com.myide.backend.repository.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final ReportRepository reportRepository;
    private final PostRepository postRepository;


    // ==========================================
    // 게시글 신고
    // ==========================================

    @Transactional
    public ReportDto.Response reportPost(
            Long postId,
            ReportDto.CreateRequest request,
            Long currentUserId
    ) {

        // ==========================================
        // 1. 로그인 확인
        // ==========================================

        if (currentUserId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }


        // ==========================================
        // 2. 신고 사유 확인
        // ==========================================

        if (request == null || request.getReason() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "신고 사유를 선택해주세요."
            );
        }


        // ==========================================
        // 3. 상세 신고 내용 길이 검사
        // ==========================================

        String content = request.getContent();

        if (content != null) {

            content = content.trim();

            if (content.length() > 500) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "상세 신고 내용은 500자 이하로 입력해주세요."
                );
            }

            // 빈 문자열은 null로 저장
            if (content.isEmpty()) {
                content = null;
            }
        }


        // ==========================================
        // 4. 게시글 조회
        // ==========================================

        Post post = postRepository.findById(postId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "게시글을 찾을 수 없습니다."
                        )
                );


        // ==========================================
        // 5. 자기 게시글 신고 방지
        // ==========================================

        if (post.getAuthorId().equals(currentUserId)) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "본인이 작성한 게시글은 신고할 수 없습니다."
            );
        }


        // ==========================================
        // 6. 중복 신고 확인
        // ==========================================

        boolean alreadyReported =
                reportRepository.existsByPost_IdAndReporterId(
                        postId,
                        currentUserId
                );

        if (alreadyReported) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "이미 신고한 게시글입니다."
            );
        }


        // ==========================================
        // 7. 신고 저장
        // ==========================================

        Report report = Report.builder()
                .post(post)
                .reporterId(currentUserId)
                .reason(request.getReason())
                .content(content)
                .build();

        Report savedReport =
                reportRepository.save(report);


        // ==========================================
        // 8. 응답
        // ==========================================

        return ReportDto.Response.builder()
                .id(savedReport.getId())
                .postId(post.getId())
                .reporterId(savedReport.getReporterId())
                .reason(savedReport.getReason())
                .content(savedReport.getContent())
                .status(savedReport.getStatus())
                .createdAt(savedReport.getCreatedAt())
                .build();
    }
}