package com.myide.backend.service.aireport;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI 가 쓴 보고서를 문자열로만 빠르게 검사한다. 검사를 위해 AI 를 다시 부르지 않는다.
 *
 * 무엇을 보는가:
 *  - 7개 항목이 다 있는지, 잘리지 않았는지
 *  - API 명세·ERD 컬럼·JSON 을 그대로 옮겨 적은 줄이 많은지
 *  - 글이 문단 중심인지(목록만 늘어놓지 않았는지)
 *  - 개발일지에 구현 기록이 없는 기능을 "구현했다"고, 혹은 반대로 "미구현"이라고 단정했는지
 *  - 영문 기술 용어를 한글 발음으로 옮겨 썼는지, 마크다운 기호를 썼는지
 *
 * 응답 계약(문자열 하나)은 그대로다. 항목이 빠지면 빈 항목을 붙여 7개를 맞추고,
 * 나머지 품질 문제는 로그로 남긴다. 본문을 고쳐 쓰면 사실이 틀어질 수 있다.
 */
@Component
public class FinalReportValidator {

    static final double MAX_SPEC_COPY_RATIO = 0.15;
    static final double MIN_PROSE_RATIO = 0.5;

    private static final Pattern API_LINE =
            Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE)\\s+/|/api/");
    private static final Pattern COLUMN_LINE = Pattern.compile(
            "\\b(BIGINT|VARCHAR|INT|INTEGER|TEXT|DATETIME|TIMESTAMP|BOOLEAN|DATE|DECIMAL|CHAR|LONGTEXT)\\b");
    private static final Pattern JSON_LINE = Pattern.compile("\\{\\s*\"|\"\\s*:\\s*[\"\\[{\\d]");
    private static final Pattern LIST_LINE = Pattern.compile("^\\s*([-*•·▪■]|\\(?\\d+\\))\\s+");
    private static final Pattern HEADING_LINE = Pattern.compile("^\\s*#{0,6}\\s*\\**\\s*\\d+(\\.\\d+)*\\s*[.)]?\\s+\\S.{0,40}$");

    private static final Pattern CLAIM = Pattern.compile("구현(하였|했|되었|됐|을 완료|이 완료|완료)");
    // "설계"는 넣지 않는다. "설계상 연결되어 구현되었다"가 바로 잡아야 할 혼동이다.
    private static final Pattern HEDGE = Pattern.compile("않|못|미구현|미완성|예정|필요|여부|아직|계획");

    /** 기록이 없을 뿐인 기능을 만들지 않았다고 단정하는 표현. "확인"이 함께 있으면 단정이 아니다. */
    private static final Pattern DENIAL = Pattern.compile("미구현|구현되지 않|구현하지 않|구현하지 못|구현 안 ");

    /**
     * 3.5 Flash-Lite 가 영문 기술 용어를 한글 발음으로 옮겨 쓴 사례(실측)와 그 변형.
     * "ERD"를 "에이알디"로 틀리게 쓰기도 했다.
     */
    private static final Pattern TRANSLITERATION = Pattern.compile(
            "에이피아이|제이더블유티|리액트|스프링\\s?부트|스프링\\s?보안|마이\\s?SQL|마이에스큐엘|제이피에이"
                    + "|이알디|에이알디|쿼리디에스엘|도커\\s?콤포즈|웹소켓|풀캘린더");

    /** 줄 첫머리의 #, 굵게(**), 코드 표시(`), 표 구분선. */
    private static final Pattern MARKDOWN = Pattern.compile("(?m)^\\s*#{1,6}\\s|\\*\\*|`|^\\s*\\|.*\\|\\s*$");

    public record Result(
            List<String> missingSections,
            boolean truncated,
            int specCopyLines,
            double specCopyRatio,
            double proseRatio,
            List<String> unsupportedClaims,
            List<String> missingKeyFeatures,
            List<String> deniedWithoutEvidence,
            List<String> transliteratedTerms,
            int markdownMarks
    ) {
        public boolean structureOk() {
            return missingSections.isEmpty() && !truncated;
        }

        public boolean qualityOk() {
            return specCopyRatio <= MAX_SPEC_COPY_RATIO && proseRatio >= MIN_PROSE_RATIO
                    && unsupportedClaims.isEmpty() && missingKeyFeatures.isEmpty()
                    && deniedWithoutEvidence.isEmpty() && transliteratedTerms.isEmpty() && markdownMarks == 0;
        }
    }

    public Result check(String report, List<ReportFeature> features, boolean truncated) {
        String text = report == null ? "" : report;

        List<String> missing = new ArrayList<>();
        for (int i = 0; i < FinalReportPromptBuilder.SECTIONS.size(); i++) {
            if (!sectionPattern(i + 1, FinalReportPromptBuilder.SECTIONS.get(i)).matcher(text).find()) {
                missing.add(FinalReportPromptBuilder.SECTIONS.get(i));
            }
        }

        int contentLines = 0;
        int specLines = 0;
        long proseChars = 0;
        long contentChars = 0;

        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || HEADING_LINE.matcher(trimmed).matches()) {
                continue;
            }

            contentLines++;
            contentChars += trimmed.length();

            if (API_LINE.matcher(trimmed).find() || COLUMN_LINE.matcher(trimmed).find()
                    || JSON_LINE.matcher(trimmed).find()) {
                specLines++;
            }
            if (!LIST_LINE.matcher(trimmed).find()) {
                proseChars += trimmed.length();
            }
        }

        double specRatio = contentLines == 0 ? 0 : (double) specLines / contentLines;
        double proseRatio = contentChars == 0 ? 0 : (double) proseChars / contentChars;

        List<String> sentences = List.of(text.split("(?<=[.!?다])\\s+|\\n"));
        List<String> unsupported = new ArrayList<>();
        List<String> missingFeatures = new ArrayList<>();
        List<String> denied = new ArrayList<>();

        for (ReportFeature feature : features) {
            List<String> heads = DevlogEvidence.heads(feature.name());
            if (heads.isEmpty()) {
                continue;
            }

            boolean mentioned = sentences.stream().anyMatch(s -> heads.stream().anyMatch(s::contains));
            if (feature.required() && !mentioned) {
                missingFeatures.add(feature.label());
            }

            if (feature.evidence() == ReportFeature.Evidence.NONE) {
                boolean claimed = sentences.stream()
                        .filter(s -> heads.stream().anyMatch(s::contains))
                        .anyMatch(s -> CLAIM.matcher(s).find() && !HEDGE.matcher(s).find());
                if (claimed) {
                    unsupported.add(feature.label());
                }

                boolean deniedOutright = sentences.stream()
                        .filter(s -> heads.stream().anyMatch(s::contains))
                        .anyMatch(s -> DENIAL.matcher(s).find() && !s.contains("확인"));
                if (deniedOutright) {
                    denied.add(feature.label());
                }
            }
        }

        List<String> transliterated = TRANSLITERATION.matcher(text).results()
                .map(match -> match.group()).distinct().toList();
        int markdownMarks = (int) MARKDOWN.matcher(text).results().count();

        return new Result(missing, truncated, specLines, specRatio, proseRatio, unsupported, missingFeatures,
                denied, transliterated, markdownMarks);
    }

    /**
     * 빠진 항목만 채운다. 화면과 PDF 가 7개 항목을 전제로 하므로 개수를 맞추되,
     * 내용은 지어내지 않고 사용자가 채우도록 남긴다.
     */
    public String completeSections(String report, Result result) {
        if (result.missingSections().isEmpty()) {
            return report;
        }

        StringBuilder builder = new StringBuilder(report.stripTrailing());
        for (String section : result.missingSections()) {
            int number = FinalReportPromptBuilder.SECTIONS.indexOf(section) + 1;
            builder.append("\n\n").append(number).append(". ").append(section)
                    .append("\n추가 작성 필요 (AI 응답에서 이 항목이 빠져 비워 두었습니다.)");
        }
        return builder.toString();
    }

    private Pattern sectionPattern(int number, String title) {
        // "1. 프로젝트 개요", "### 1. 프로젝트 개요", "**1. 프로젝트 개요**", "1) 프로젝트개요" 를 모두 받는다.
        String flexibleTitle = Pattern.quote(title).replace(" ", "\\E\\s*\\Q");
        return Pattern.compile("(?m)^\\s*#{0,6}\\s*\\**\\s*" + number + "\\s*[.)]\\s*" + flexibleTitle);
    }
}
