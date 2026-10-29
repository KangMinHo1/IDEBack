package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest.DevlogInfo;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 기능 이름으로 개발일지에서 구현 기록을 찾는다.
 *
 * 설계에서 요구사항-화면-API-표가 이어져 있어도 그건 설계가 끝났다는 뜻일 뿐이다.
 * 예전 보고서는 그 연결을 근거로 "구현했다"고 써 버렸다. 서버가 볼 수 있는 구현의
 * 흔적은 팀이 적은 개발일지뿐이므로, 여기서 찾은 것만 구현 근거로 넘긴다.
 *
 * 문자열 비교라 완벽하지 않다. 그래서 결과는 "근거 후보"로만 쓰고, 어느 일지에서
 * 찾았는지를 함께 넘겨 AI 가 내용을 보고 판단하게 한다.
 */
final class DevlogEvidence {

    /** 이름 끝에 붙어도 기능을 가리키지 못하는 말. "프로필 관리"는 "프로필"로 찾는다. */
    private static final Set<String> WEAK_WORDS = Set.of(
            "및", "기능", "관리", "처리", "등", "하기", "구현", "화면", "페이지");

    /** 같은 문장에 있으면 그 기능을 끝내지 못했다는 뜻으로 본다. */
    private static final List<String> UNFINISHED = List.of(
            "미완성", "미구현", "못했", "못하고", "못 했", "못 하고", "못한", "미뤘", "미루",
            "보류", "시도했지만", "예정");

    record Match(ReportFeature.Evidence evidence, List<DevlogInfo> devlogs) {
    }

    private DevlogEvidence() {
    }

    static Match find(String featureName, List<DevlogInfo> devlogs) {
        List<String> heads = heads(featureName);

        if (heads.isEmpty() || devlogs == null || devlogs.isEmpty()) {
            return new Match(ReportFeature.Evidence.NONE, List.of());
        }

        List<DevlogInfo> recorded = new ArrayList<>();
        List<DevlogInfo> unfinished = new ArrayList<>();

        for (DevlogInfo devlog : devlogs) {
            switch (judge(devlog, heads)) {
                case RECORDED -> recorded.add(devlog);
                case PARTIAL -> unfinished.add(devlog);
                case NONE -> { }
            }
        }

        if (!recorded.isEmpty()) {
            return new Match(ReportFeature.Evidence.RECORDED, recorded);
        }
        if (!unfinished.isEmpty()) {
            return new Match(ReportFeature.Evidence.PARTIAL, unfinished);
        }
        return new Match(ReportFeature.Evidence.NONE, List.of());
    }

    /**
     * 기능 이름에서 찾을 말을 고른다.
     *
     * 우리말 명사구는 끝말이 중심어다("실시간 채팅"의 "채팅"). 앞말까지 모두
     * 요구하면 일지가 "채팅을 시도했다"처럼 줄여 쓸 때 놓치고, 아무 말이나 하나만
     * 맞아도 되게 하면 "모임 알림"이 "모임 일정"에 걸린다. '/'로 나뉜 이름은
     * 각각을 따로 본다("참가 신청/승인" → 신청, 승인).
     */
    static List<String> heads(String featureName) {
        if (!StringUtils.hasText(featureName)) {
            return List.of();
        }

        List<String> heads = new ArrayList<>();

        for (String segment : featureName.split("/")) {
            String[] words = segment.trim().split("[\\s·,()\\[\\]]+");

            for (int i = words.length - 1; i >= 0; i--) {
                String word = words[i].trim();

                if (word.length() >= 2 && !WEAK_WORDS.contains(word)) {
                    heads.add(word);
                    break;
                }
            }
        }

        return heads;
    }

    static boolean isUnfinished(String sentence) {
        return UNFINISHED.stream().anyMatch(sentence::contains);
    }

    /**
     * 일지 하나를 판정한다.
     * 본문 문장에 기능이 나오면 그 문장들로 판단하고, 제목에만 나오면 본문
     * 어디에든 미완성 표현이 있는지로 판단한다("채팅 기능 검토" + "구현하지 못했다").
     */
    private static ReportFeature.Evidence judge(DevlogInfo devlog, List<String> heads) {
        String title = devlog.getTitle() == null ? "" : devlog.getTitle();
        String summary = devlog.getSummary() == null ? "" : devlog.getSummary();

        boolean mentionedInBody = false;
        boolean positiveInBody = false;

        for (String sentence : summary.split("[.!?\\n]+")) {
            if (heads.stream().anyMatch(sentence::contains)) {
                mentionedInBody = true;
                positiveInBody |= !isUnfinished(sentence);
            }
        }

        if (mentionedInBody) {
            return positiveInBody ? ReportFeature.Evidence.RECORDED : ReportFeature.Evidence.PARTIAL;
        }

        if (heads.stream().anyMatch(title::contains)) {
            return isUnfinished(summary) || isUnfinished(title)
                    ? ReportFeature.Evidence.PARTIAL
                    : ReportFeature.Evidence.RECORDED;
        }

        return ReportFeature.Evidence.NONE;
    }
}
