package com.myide.backend.service.design.codegen;

import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenV2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "이 코드는 어느 요구사항에서 나왔는가"를 주석으로 남기기 위한 조회.
 *
 * 설계와 코드가 따로 놀지 않게 하는 것이 이 기능의 목적이므로, 생성된 파일
 * 안에 근거가 적혀 있어야 의미가 있다. 파일만 나오고 왜 있는지 알 수 없으면
 * 결국 설계를 다시 안 보게 된다.
 */
public final class Traceability {

    private Traceability() {
    }

    /** "R-01 로그인" 형태의 라벨. code 가 없으면 이름만 쓴다. */
    public static String label(RequirementV2 requirement) {
        return requirement.code().isBlank()
                ? requirement.name()
                : requirement.code() + " " + requirement.name();
    }

    public static List<String> requirementLabels(DesignModelV2 model, List<String> requirementIds) {
        Map<String, RequirementV2> byId = requirementIndex(model);
        List<String> labels = new ArrayList<>();

        for (String id : requirementIds) {
            RequirementV2 requirement = byId.get(id);
            if (requirement != null) {
                labels.add(label(requirement));
            }
        }

        return labels;
    }

    /**
     * 표 하나에 닿는 요구사항.
     *
     * 표는 요구사항을 직접 가리키지 않는다. 그 표를 쓰는 API 를 거쳐야
     * 닿는데, 이 한 다리가 바로 설계 점검이 검사하는 사슬이다.
     */
    public static List<String> requirementLabelsForTable(DesignModelV2 model, String tableId) {
        Map<String, RequirementV2> byId = requirementIndex(model);
        Set<String> labels = new LinkedHashSet<>();

        for (ApiSpecV2 api : model.apis()) {
            if (!api.tableIds().contains(tableId)) {
                continue;
            }

            for (String requirementId : api.requirementIds()) {
                RequirementV2 requirement = byId.get(requirementId);
                if (requirement != null) {
                    labels.add(label(requirement));
                }
            }
        }

        return new ArrayList<>(labels);
    }

    /**
     * 표 하나에 닿는 요구사항의 id.
     *
     * 라벨(사람이 읽는 글자)과 달리 id 는 미리보기에서 파일을 기능별로 묶는 열쇠로 쓴다.
     * 라벨은 이름이 바뀌면 같이 바뀌고 같은 이름이 둘일 수도 있어서 묶음 기준이 못 된다.
     */
    public static List<String> requirementIdsForTable(DesignModelV2 model, String tableId) {
        Set<String> ids = new LinkedHashSet<>();

        for (ApiSpecV2 api : model.apis()) {
            if (api.tableIds().contains(tableId)) {
                ids.addAll(api.requirementIds());
            }
        }

        return new ArrayList<>(ids);
    }

    /** API 여러 개가 걸려 있는 요구사항 id 를 겹치지 않게 모은다. */
    public static List<String> requirementIdsOfApis(List<ApiSpecV2> apis) {
        Set<String> ids = new LinkedHashSet<>();
        apis.forEach(api -> ids.addAll(api.requirementIds()));
        return new ArrayList<>(ids);
    }

    /** API 를 부르는 화면 이름들. 컨트롤러 주석에 "어디서 쓰는지"를 남긴다. */
    public static List<String> screenLabels(DesignModelV2 model, ApiSpecV2 api) {
        List<String> labels = new ArrayList<>();

        for (ScreenV2 screen : model.screens()) {
            if (screen.apiIds().contains(api.id()) || api.screenIds().contains(screen.id())) {
                String route = screen.key().isBlank() ? "" : " (" + screen.key() + ")";
                labels.add(screen.name() + route);
            }
        }

        return labels;
    }

    private static Map<String, RequirementV2> requirementIndex(DesignModelV2 model) {
        Map<String, RequirementV2> byId = new LinkedHashMap<>();
        model.requirements().forEach(requirement -> byId.put(requirement.id(), requirement));
        return byId;
    }
}
