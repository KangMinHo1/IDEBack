package com.myide.backend.service.design.doctor;

import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.DoctorReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 설계 검사를 한곳에서 돌린다.
 *
 * 검사를 서버에만 두는 이유는 세 가지다.
 *  1. 코드 생성이 이 결과로 막히는데, 클라이언트 판정은 신뢰할 수 없다.
 *  2. 최종 보고서도 같은 결과를 쓴다.
 *  3. 자바와 자바스크립트에 같은 규칙을 두 벌 두면 서른 개를 넘는 순간 반드시 어긋난다.
 *
 * 저장되지 않은 편집 중 상태도 검사해야 하므로 DB 를 읽지 않는다.
 * 요청 본문으로 받은 문서만 보고 답한다.
 */
@Service
@RequiredArgsConstructor
public class DesignDoctorService {

    private final List<DesignRule> rules;

    public DoctorReport inspect(DesignModelV2 model) {
        DesignModelV2 safe = model == null ? DesignModelV2.empty() : model;
        DesignIndex index = new DesignIndex(safe);

        List<Finding> findings = new ArrayList<>();
        rules.forEach(rule -> findings.addAll(rule.check(safe, index)));

        findings.sort(Comparator
                .comparingInt((Finding finding) -> finding.severity().ordinal())
                .thenComparing(Finding::targetKind)
                .thenComparing(Finding::ruleId));

        long errors = findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        long warnings = findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
        long infos = findings.stream().filter(f -> f.severity() == Severity.INFO).count();

        return new DoctorReport(
                findings,
                (int) errors,
                (int) warnings,
                (int) infos,
                errors > 0,
                DesignProgress.measure(safe, index)
        );
    }
}
