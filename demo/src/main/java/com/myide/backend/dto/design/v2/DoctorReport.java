package com.myide.backend.dto.design.v2;

import com.myide.backend.service.design.doctor.Finding;

import java.util.List;

/**
 * 설계 점검 결과.
 *
 * codegenBlocked 가 true 면 코드 생성을 막는다.
 * 깨진 설계에서 나온 코드는 안 만드는 편이 낫다.
 *
 * progress 를 여기 함께 담는 이유는, 검사와 진행도가 같은 문서를 같은 인덱스로 보기
 * 때문이다. 왕복을 한 번 더 하거나 프론트에서 따로 세면 두 숫자가 어긋난다.
 */
public record DoctorReport(
        List<Finding> findings,
        int errorCount,
        int warningCount,
        int infoCount,
        boolean codegenBlocked,
        ProgressReport progress
) {
    public DoctorReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
        progress = progress == null ? ProgressReport.empty() : progress;
    }
}
