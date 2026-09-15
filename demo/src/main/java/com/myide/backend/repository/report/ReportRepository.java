package com.myide.backend.repository.report;

import com.myide.backend.domain.report.Report;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // 같은 사용자가 같은 게시글을 이미 신고했는지 확인
    boolean existsByPost_IdAndReporterId(
            Long postId,
            Long reporterId
    );

    // 게시글 삭제 시 연결된 신고 데이터 먼저 삭제
    void deleteByPost_Id(Long postId);
}