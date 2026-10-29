package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aireport.FinalReportDraftRequest.DevlogInfo;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignMetaV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ErdV2;
import com.myide.backend.dto.design.v2.PointV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenTransitionV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.repository.design.DesignDocSnapshotRepository;
import com.myide.backend.service.design.DesignModelCodec;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import com.myide.backend.service.design.doctor.DesignRule;
import com.myide.backend.service.design.doctor.rules.ApiRules;
import com.myide.backend.service.design.doctor.rules.ErdRules;
import com.myide.backend.service.design.doctor.rules.RequirementRules;
import com.myide.backend.service.design.doctor.rules.ScreenRules;
import com.myide.backend.service.design.doctor.rules.TraceRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최종 보고서에 들어갈 설계 자료를 확인한다.
 *
 * 자료가 명세 원문이면 AI 도 원문을 옮겨 적는다. 그래서 여기서는 무엇이 들어가는지
 * 만큼 <b>무엇이 빠지는지</b>(요청·응답 JSON, 컬럼 자료형, 경로 나열)도 함께 본다.
 */
class DesignReportSectionBuilderTest {

    private static final String WORKSPACE = "ws-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DesignModelCodec codec = new DesignModelCodec(objectMapper);

    private DesignDocSnapshotRepository snapshotRepository;
    private DesignReportSectionBuilder builder;

    @BeforeEach
    void setUp() {
        snapshotRepository = Mockito.mock(DesignDocSnapshotRepository.class);

        List<DesignRule> rules = List.of(
                new RequirementRules(), new ScreenRules(), new ApiRules(),
                new ErdRules(), new TraceRules());

        builder = new DesignReportSectionBuilder(
                snapshotRepository, codec, new DesignDoctorService(rules));
    }

    @Test
    @DisplayName("저장된 설계가 없으면 예전 경로를 쓰도록 null 을 돌려준다")
    void returnsNullWithoutSnapshot() {
        Mockito.when(snapshotRepository.findProjectionJsonByWorkspaceUuid(WORKSPACE))
                .thenReturn(Optional.empty());

        assertThat(builder.build(WORKSPACE, List.of())).isNull();
    }

    @Test
    @DisplayName("저장은 됐는데 내용이 비었으면 굳이 빈 글을 넣지 않는다")
    void returnsNullForEmptyDesign() {
        givenSnapshot(codec.toJson(DesignModelV2.empty()));

        assertThat(builder.build(WORKSPACE, List.of())).isNull();
    }

    @Test
    @DisplayName("망가진 저장본이면 보고서를 막지 않고 예전 경로로 넘긴다")
    void survivesBrokenSnapshot() {
        givenSnapshot("{ 이건 JSON 이 아니다");

        assertThat(builder.build(WORKSPACE, List.of())).isNull();
    }

    @Test
    @DisplayName("Yjs 바이너리까지 읽는 엔티티 조회를 쓰지 않는다")
    void readsProjectionOnly() {
        givenSnapshot(codec.toJson(sample()));

        builder.build(WORKSPACE, List.of());

        Mockito.verify(snapshotRepository, Mockito.never()).findByWorkspace_Uuid(Mockito.anyString());
    }

    @Test
    @DisplayName("기능 단위로 묶고 화면 흐름·점검 결과·구현 근거를 함께 넣는다")
    void groupsByFeature() {
        givenSnapshot(codec.toJson(sample()));

        String text = builder.build(WORKSPACE, List.of()).text();

        assertThat(text).contains("[기능별 정리]");
        assertThat(text).contains("■ 회원");
        assertThat(text).contains("- R-01 로그인 [필수]");
        assertThat(text).contains("API 역할: 로그인한다");
        assertThat(text).contains("사용 데이터: users");

        assertThat(text).contains("[핵심 사용자 흐름]");
        assertThat(text).contains("로그인 화면 →(로그인 버튼 클릭) 글 상세");

        assertThat(text).contains("[설계 점검 결과]");
        assertThat(text).contains("[구현 근거 요약]");
    }

    @Test
    @DisplayName("요청·응답 JSON 과 컬럼 자료형은 넘기지 않는다")
    void dropsSpecDetails() {
        givenSnapshot(codec.toJson(sample()));

        String text = builder.build(WORKSPACE, List.of()).text();

        assertThat(text).doesNotContain("\"email\"");
        assertThat(text).doesNotContain("accessToken");
        assertThat(text).doesNotContain("BIGINT");
        assertThat(text).doesNotContain("VARCHAR");
        assertThat(text).doesNotContain("/api/users/login");

        // 업무 의미가 있는 컬럼은 이름과 설명만 남는다.
        assertThat(text).contains("email(로그인 이메일)");
    }

    @Test
    @DisplayName("설계가 이어져 있어도 개발일지 기록이 없으면 구현으로 보지 않는다")
    void separatesDesignFromImplementation() {
        givenSnapshot(codec.toJson(sample()));

        DesignReportSectionBuilder.DesignBrief brief = builder.build(WORKSPACE, List.of());

        assertThat(brief.text()).contains("설계 연결: 화면·API·테이블까지 연결됨");
        assertThat(brief.text()).contains("구현 근거: 개발일지에 기록 없음 — 구현 여부 확인 필요");
        assertThat(brief.text()).doesNotContain("미구현");
        assertThat(brief.features()).singleElement()
                .extracting(ReportFeature::evidence).isEqualTo(ReportFeature.Evidence.NONE);

        DesignReportSectionBuilder.DesignBrief withLog = builder.build(WORKSPACE,
                List.of(devlog("2026-04-01", "로그인 구현", "JWT 로 로그인을 만들었다.")));

        assertThat(withLog.text()).contains("개발일지에 구현 기록 있음 (2026-04-01 「로그인 구현」)");
        assertThat(withLog.features()).singleElement()
                .extracting(ReportFeature::evidence).isEqualTo(ReportFeature.Evidence.RECORDED);
    }

    @Test
    @DisplayName("사슬이 끊긴 요구사항은 어디서 끊겼는지 적는다")
    void reportsBrokenTrace() {
        DesignModelV2 model = sample();

        List<RequirementV2> requirements = new ArrayList<>(model.requirements());
        requirements.add(new RequirementV2("req_9", "R-09", "알림", "알림 받기",
                "새 글이 올라오면 알린다", "should", List.of(), List.of()));

        givenSnapshot(codec.toJson(new DesignModelV2(model.schemaVersion(), model.meta(),
                requirements, model.screens(), model.screenTransitions(), model.apis(),
                model.erd())));

        String text = builder.build(WORKSPACE, List.of()).text();

        assertThat(text).contains("- R-09 알림 받기 [권장]");
        assertThat(text).contains("설계 연결: 담당 화면 없음, 담당 API 없음");
        assertThat(text).contains("이 중 1개가 설계상 화면·API·테이블까지 연결됨");
    }

    @Test
    @DisplayName("대표 프로젝트 자료가 예전보다 짧아진다")
    void representativeBriefIsCompact() throws Exception {
        String projection = new String(getClass()
                .getResourceAsStream("/aireport/bench-projection.json").readAllBytes(), StandardCharsets.UTF_8);
        givenSnapshot(projection);

        String text = builder.build(WORKSPACE, List.of()).text();

        // 예전 방식의 설계 부분은 같은 자료로 8,366자였다(요청·응답 JSON, 컬럼 자료형 포함).
        assertThat(text.length()).isLessThan(5000);
        assertThat(text).contains("스터디 목록 →");
        assertThat(text).contains("요구사항과 연결되지 않은 API: 스터디 수정(로그인 필요), 스터디 삭제(로그인 필요)");
    }

    private void givenSnapshot(String projectionJson) {
        Mockito.when(snapshotRepository.findProjectionJsonByWorkspaceUuid(WORKSPACE))
                .thenReturn(Optional.of(projectionJson));
    }

    static DevlogInfo devlog(String date, String title, String summary) {
        DevlogInfo devlog = new DevlogInfo();
        devlog.setDate(date);
        devlog.setTitle(title);
        devlog.setSummary(summary);
        return devlog;
    }

    private DesignModelV2 sample() {
        ColumnV2 id = new ColumnV2("col_1", "id", "BIGINT", null,
                false, true, false, "", "");
        ColumnV2 email = new ColumnV2("col_2", "email", "VARCHAR", 255,
                false, false, false, "", "로그인 이메일");

        TableV2 users = new TableV2("tbl_user", "users", "User", "서비스를 쓰는 사람",
                List.of(id, email), new PointV2(0, 0));

        RequirementV2 login = new RequirementV2("req_1", "R-01", "회원", "로그인",
                "이메일로 로그인한다", "must", List.of("scr_login"), List.of("api_login"));

        ScreenV2 loginScreen = new ScreenV2("scr_login", "/login", "로그인 화면",
                "이메일과 비밀번호를 넣는다", "page", true, false,
                List.of("req_1"), List.of("api_login"), new PointV2(0, 0));

        ScreenV2 detailScreen = new ScreenV2("scr_detail", "/posts", "글 상세",
                "", "page", false, false, List.of(), List.of(), new PointV2(300, 0));

        ScreenTransitionV2 transition = new ScreenTransitionV2("trn_1", "scr_login",
                "scr_detail", "로그인 버튼 클릭", "submit", "", List.of());

        ApiSpecV2 loginApi = new ApiSpecV2("api_login", "POST", "/api/users/login",
                "로그인한다", "{\"email\":\"a@b.c\"}", "{\"accessToken\":\"x\"}", false, "R",
                List.of("req_1"), List.of("scr_login"), List.of("tbl_user"));

        return new DesignModelV2(2, DesignMetaV2.empty(),
                List.of(login), List.of(loginScreen, detailScreen), List.of(transition),
                List.of(loginApi), new ErdV2(List.of(users), List.of()));
    }
}
