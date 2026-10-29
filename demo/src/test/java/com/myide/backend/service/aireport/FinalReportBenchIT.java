package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import com.myide.backend.repository.design.DesignDocSnapshotRepository;
import com.myide.backend.repository.design.DesignSnapshotVersion;
import com.myide.backend.service.design.DesignModelCodec;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import com.myide.backend.service.design.doctor.rules.ApiRules;
import com.myide.backend.service.design.doctor.rules.ErdRules;
import com.myide.backend.service.design.doctor.rules.RequirementRules;
import com.myide.backend.service.design.doctor.rules.ScreenRules;
import com.myide.backend.service.design.doctor.rules.TraceRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 진짜 Gemini 로 보고서 생성 방식과 모델을 비교한다. 평소에는 건너뛴다.
 *
 * <pre>
 * FINAL_REPORT_BENCH=1 \
 * BENCH_OLD_PROMPT=(예전 방식 프롬프트 파일) \
 * BENCH_OUT=(결과를 남길 폴더) \
 * BENCH_MODELS=gemini-2.5-flash,gemini-3.5-flash-lite \
 * BENCH_RUNS=3 \
 * ./gradlew test --tests "*FinalReportBenchIT"
 * </pre>
 *
 * 입력은 익명화된 대표 프로젝트(src/test/resources/aireport)다. 예전 방식은 코드가
 * 이미 바뀌었으므로, 바꾸기 전 코드가 같은 자료로 만든 프롬프트 파일을 그대로 보낸다
 * (지침과 자료를 한 덩어리로 보내던 예전 요청 모양 그대로).
 * 새 방식은 실제 FinalReportService 를 그대로 돌린다(저장소만 가짜).
 * 키는 application-secret.yml 에서 읽고 어디에도 출력하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "FINAL_REPORT_BENCH", matches = "1")
class FinalReportBenchIT {

    private static final String WORKSPACE = "bench-ws";

    private final ObjectMapper objectMapper = new ObjectMapper();

    record Run(String config, double totalMs, double apiMs, double prepMs, int in, int out, int think,
               String finish, FinalReportValidator.Result check, int chars) {
    }

    @Test
    void compare() throws Exception {
        String secret = Files.readString(Path.of("src/main/resources/application-secret.yml"));
        GeminiReportClient client = new GeminiReportClient(objectMapper);
        ReflectionTestUtils.setField(client, "apiKey", find(secret, "api-key:\\s*\"?([^\"\\s]+)"));
        ReflectionTestUtils.setField(client, "url", find(secret, "url:\\s*\"?([^\"\\s]+generateContent)"));

        String projection = resource("/aireport/bench-projection.json");
        FinalReportDraftRequest request = objectMapper.readValue(
                resource("/aireport/bench-request.json"), FinalReportDraftRequest.class);

        DesignDocSnapshotRepository repository = Mockito.mock(DesignDocSnapshotRepository.class);
        Mockito.when(repository.findVersionByWorkspaceUuid(WORKSPACE))
                .thenReturn(Optional.of(new DesignSnapshotVersion(1L, 7L, LocalDateTime.of(2026, 6, 30, 12, 0))));
        Mockito.when(repository.findProjectionJsonByWorkspaceUuid(WORKSPACE)).thenReturn(Optional.of(projection));

        DesignReportSectionBuilder sectionBuilder = new DesignReportSectionBuilder(repository,
                new DesignModelCodec(objectMapper), new DesignDoctorService(List.of(new RequirementRules(),
                new ScreenRules(), new ApiRules(), new ErdRules(), new TraceRules())));
        FinalReportPromptBuilder promptBuilder = new FinalReportPromptBuilder();
        FinalReportValidator validator = new FinalReportValidator();
        ReportBriefCache cache = new ReportBriefCache(objectMapper);
        FinalReportService service = new FinalReportService(promptBuilder, client, sectionBuilder, cache, validator);

        // 품질 지표는 두 방식 모두 같은 기능 목록(구현 근거 포함)으로 잰다.
        List<ReportFeature> features = promptBuilder.buildBrief(request,
                sectionBuilder.build(WORKSPACE, request.getDevlogs())).features();

        String oldPromptPath = System.getenv("BENCH_OLD_PROMPT");
        String oldPrompt = oldPromptPath == null ? "" : Files.readString(Path.of(oldPromptPath));
        Path out = Path.of(System.getenv().getOrDefault("BENCH_OUT", "build/report-bench"));
        Files.createDirectories(out);
        List<String> models = List.of(System.getenv().getOrDefault("BENCH_MODELS",
                "gemini-2.5-flash,gemini-3.5-flash-lite").split(","));
        int runs = Integer.parseInt(System.getenv().getOrDefault("BENCH_RUNS", "3"));
        // 호출 수를 줄이고 싶을 때 한쪽 방식만 돌린다(예: BENCH_APPROACHES=new).
        List<String> approaches = List.of(System.getenv().getOrDefault("BENCH_APPROACHES", "old,new").split(","));

        Files.writeString(out.resolve("new_prompt.txt"), promptBuilder.buildInstructions() + "\n\n"
                + promptBuilder.buildBrief(request, sectionBuilder.build(WORKSPACE, request.getDevlogs())).input());

        List<Run> results = new ArrayList<>();

        for (String model : models) {
            for (String approach : approaches) {
                String config = approach + "/" + model;

                for (int i = 1; i <= runs; i++) {
                    Run run;

                    if (approach.equals("old")) {
                        long startedAt = System.nanoTime();
                        GeminiReportClient.ReportGeneration generation = client.generate("", oldPrompt, model);
                        double total = (System.nanoTime() - startedAt) / 1e6;
                        FinalReportValidator.Result check =
                                validator.check(generation.text(), features, generation.truncated());
                        run = new Run(config, total, generation.apiMillis(), 0, generation.promptTokens(),
                                generation.outputTokens(), generation.thoughtTokens(), generation.finishReason(),
                                check, generation.text().length());
                        Files.writeString(out.resolve("old_" + model + "_" + i + ".txt"), generation.text());
                    } else {
                        ReflectionTestUtils.setField(client, "reportModel", model);
                        FinalReportService.Outcome outcome = service.generate(WORKSPACE, request);
                        GeminiReportClient.ReportGeneration generation = outcome.generation();
                        run = new Run(config, outcome.totalMillis(), generation.apiMillis(),
                                outcome.prepareMillis(), generation.promptTokens(), generation.outputTokens(),
                                generation.thoughtTokens(), generation.finishReason(), outcome.check(),
                                outcome.draft().length());
                        Files.writeString(out.resolve("new_" + model + "_" + i + ".txt"), outcome.draft());
                    }

                    results.add(run);
                    System.out.printf("[BENCH] %-28s #%d total=%.0fms api=%.0fms prep=%.1fms in=%d out=%d think=%d "
                                    + "finish=%s chars=%d missing=%s copy=%d(%.0f%%) prose=%.0f%% claims=%s missingKey=%s%n",
                            config, i, run.totalMs(), run.apiMs(), run.prepMs(), run.in(), run.out(), run.think(),
                            run.finish(), run.chars(), run.check().missingSections(), run.check().specCopyLines(),
                            run.check().specCopyRatio() * 100, run.check().proseRatio() * 100,
                            run.check().unsupportedClaims(), run.check().missingKeyFeatures());
                }
            }
        }

        Map<String, List<Run>> byConfig = new LinkedHashMap<>();
        results.forEach(run -> byConfig.computeIfAbsent(run.config(), key -> new ArrayList<>()).add(run));

        System.out.println("[BENCH] 설정 | 전체ms | APIms | 입력토큰 | 출력토큰 | 글자수 | 명세복사% | 문단% | 7항목 | 근거없는주장 | 핵심누락");
        byConfig.forEach((config, list) -> System.out.printf(
                "[BENCH] %s | %s | %s | %s | %s | %s | %s | %s | %d/%d | %d | %d%n",
                config, stat(list, Run::totalMs), stat(list, Run::apiMs), stat(list, r -> r.in()),
                stat(list, r -> r.out()), stat(list, r -> r.chars()),
                stat(list, r -> r.check().specCopyRatio() * 100), stat(list, r -> r.check().proseRatio() * 100),
                list.stream().filter(r -> r.check().missingSections().isEmpty()).count(), list.size(),
                list.stream().mapToInt(r -> r.check().unsupportedClaims().size()).sum(),
                list.stream().mapToInt(r -> r.check().missingKeyFeatures().size()).sum()));
    }

    private static String stat(List<Run> runs, ToDoubleFunction<Run> metric) {
        double mean = runs.stream().mapToDouble(metric).average().orElse(0);
        double variance = runs.stream().mapToDouble(r -> Math.pow(metric.applyAsDouble(r) - mean, 2))
                .average().orElse(0);
        return String.format("%.0f±%.0f", mean, Math.sqrt(variance));
    }

    private String resource(String path) throws Exception {
        return new String(getClass().getResourceAsStream(path).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String find(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("application-secret.yml 에서 값을 찾지 못했습니다: " + regex);
        }
        return matcher.group(1);
    }
}
