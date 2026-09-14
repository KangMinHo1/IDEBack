package com.myide.backend.service.design.doctor;

import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ProgressReport;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.StageProgress;
import com.myide.backend.dto.design.v2.TableV2;

import java.util.List;

/**
 * 설계가 얼마나 채워졌는지 센다.
 *
 * 점검 규칙과 같은 자리에 두고 같은 DesignIndex 를 쓴다. 규칙이 이미 "무엇이 비었는지"를
 * 항목마다 알고 있어서, 그 판단을 뒤집으면 그대로 진행도가 된다. 새로 모을 데이터가 없다.
 *
 * 프론트에서 따로 계산하지 않는 이유는 최종 보고서가 같은 숫자를 써야 하기 때문이다.
 * 두 곳에서 계산하면 화면은 62% 인데 보고서는 58% 가 되는 사고가 난다.
 *
 * 체크칸에 무엇을 넣고 무엇을 뺐는가
 * ------------------------------
 * 원칙은 하나다 — 모든 항목에 예외 없이 해당되는 것만 넣는다. 예외가 있으면 100% 에
 * 영원히 닿을 수 없고, 닿을 수 없는 목표는 사람이 보지 않는다.
 *
 * 그래서 이런 것들을 뺐다.
 *  - "나가는 흐름이 없다" : 마지막 화면은 없는 것이 정상이다.
 *  - "이 API 를 부르는 화면이 없다" : 배치나 내부용 API 는 화면이 없는 것이 정상이다.
 *  - 경로와 이름 관례 : 팀 규칙에 따라 옳고 그름이 달라진다.
 *  - 오류 전부 : "덜 했다"가 아니라 "틀렸다"라서 진행도가 아니라 빨간 배지로 센다.
 */
public final class DesignProgress {

    private DesignProgress() {
    }

    public static ProgressReport measure(DesignModelV2 model, DesignIndex index) {
        if (model == null) {
            return ProgressReport.empty();
        }

        return ProgressReport.of(
                requirements(model.requirements()),
                screens(model.screens(), index),
                apis(model.apis()),
                tables(model.erd() == null ? List.of() : model.erd().tables(), index)
        );
    }

    /** 기능명 · 설명 · 화면 연결 · API 연결 */
    private static StageProgress requirements(List<RequirementV2> items) {
        int perItem = 4;
        int passed = 0;
        int done = 0;

        for (RequirementV2 item : items) {
            int ok = 0;

            if (!isBlank(item.name())) ok++;
            if (trimmedLength(item.description()) >= 10) ok++;
            if (!isEmpty(item.screenIds())) ok++;
            if (!isEmpty(item.apiIds())) ok++;

            passed += ok;
            if (ok == perItem) done++;
        }

        return StageProgress.of(items.size(), done, passed, items.size() * perItem);
    }

    /** 라우트 경로 · 요구사항 연결 · 시작 화면에서 도달 가능 */
    private static StageProgress screens(List<ScreenV2> items, DesignIndex index) {
        int perItem = 3;
        int passed = 0;
        int done = 0;

        for (ScreenV2 item : items) {
            int ok = 0;

            if (!isBlank(item.key())) ok++;
            if (!isEmpty(item.requirementIds())) ok++;

            // 시작 화면 자신은 도달 여부를 따지지 않는다. 거기서 출발하기 때문이다.
            if (item.isEntry() || index.isScreenReachable(item.id())) ok++;

            passed += ok;
            if (ok == perItem) done++;
        }

        return StageProgress.of(items.size(), done, passed, items.size() * perItem);
    }

    /** 경로 · 요구사항 연결 · 테이블 연결 · 응답 예시 */
    private static StageProgress apis(List<ApiSpecV2> items) {
        int perItem = 4;
        int passed = 0;
        int done = 0;

        for (ApiSpecV2 item : items) {
            int ok = 0;

            if (!isBlank(item.endpoint())) ok++;
            if (!isEmpty(item.requirementIds())) ok++;
            if (!isEmpty(item.tableIds())) ok++;
            if (!isBlank(item.response())) ok++;

            passed += ok;
            if (ok == perItem) done++;
        }

        return StageProgress.of(items.size(), done, passed, items.size() * perItem);
    }

    /** 컬럼 1개 이상 · 기본키 · 이 테이블을 쓰는 API */
    private static StageProgress tables(List<TableV2> items, DesignIndex index) {
        int perItem = 3;
        int passed = 0;
        int done = 0;

        for (TableV2 item : items) {
            int ok = 0;

            List<ColumnV2> columns = item.columns();

            if (!columns.isEmpty()) ok++;
            if (columns.stream().anyMatch(ColumnV2::isPk)) ok++;
            if (index.isTableUsedByApi(item.id())) ok++;

            passed += ok;
            if (ok == perItem) done++;
        }

        return StageProgress.of(items.size(), done, passed, items.size() * perItem);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int trimmedLength(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private static boolean isEmpty(List<String> value) {
        return value == null || value.isEmpty();
    }
}
