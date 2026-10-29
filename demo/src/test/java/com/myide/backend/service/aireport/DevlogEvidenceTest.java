package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest.DevlogInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.myide.backend.service.aireport.DesignReportSectionBuilderTest.devlog;
import static org.assertj.core.api.Assertions.assertThat;

class DevlogEvidenceTest {

    private final List<DevlogInfo> devlogs = List.of(
            devlog("2026-03-25", "회원가입/로그인 구현", "Spring Security와 JWT로 회원가입과 로그인을 구현했다."),
            devlog("2026-05-06", "일정 캘린더", "모임 일정을 캘린더로 보여 주었다."),
            devlog("2026-06-15", "채팅 기능 검토", "WebSocket 채팅을 시도했지만 이번 학기에는 구현하지 못하고 미뤘다."),
            devlog("2026-06-20", "판매자 화면", "판매자가 주문을 확인하는 화면을 만들었다. 알림 기능은 미완성이다.")
    );

    @Test
    @DisplayName("이름의 중심어로 찾는다: '프로필 관리'는 '프로필', '참가 신청/승인'은 둘 다")
    void picksHeadWords() {
        assertThat(DevlogEvidence.heads("프로필 관리")).containsExactly("프로필");
        assertThat(DevlogEvidence.heads("참가 신청/승인")).containsExactly("신청", "승인");
        assertThat(DevlogEvidence.heads("실시간 채팅")).containsExactly("채팅");
    }

    @Test
    @DisplayName("구현했다는 문장이 있으면 구현 기록이다")
    void recorded() {
        assertThat(DevlogEvidence.find("회원가입/로그인", devlogs).evidence())
                .isEqualTo(ReportFeature.Evidence.RECORDED);
    }

    @Test
    @DisplayName("시도했지만 못했다고 적혀 있으면 미완성이다")
    void partial() {
        assertThat(DevlogEvidence.find("실시간 채팅", devlogs).evidence())
                .isEqualTo(ReportFeature.Evidence.PARTIAL);
    }

    @Test
    @DisplayName("같은 일지라도 문장 단위로 본다: 다른 기능을 만든 일지의 '알림 미완성'은 알림의 미완성이다")
    void sentenceLevel() {
        assertThat(DevlogEvidence.find("모임 알림", devlogs).evidence())
                .isEqualTo(ReportFeature.Evidence.PARTIAL);
    }

    @Test
    @DisplayName("앞말만 겹치면 근거로 보지 않는다: '모임 알림'이 '모임 일정'에 걸리지 않는다")
    void ignoresModifierOnlyMatch() {
        List<DevlogInfo> onlySchedule = List.of(devlogs.get(1));

        assertThat(DevlogEvidence.find("모임 알림", onlySchedule).evidence())
                .isEqualTo(ReportFeature.Evidence.NONE);
    }
}
