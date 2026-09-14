package com.myide.backend.service.design.codegen;

import com.myide.backend.dto.design.codegen.CodegenApplyRequest;
import com.myide.backend.dto.design.codegen.CodegenApplyResponse;
import com.myide.backend.dto.design.codegen.CodegenApplyResult;
import com.myide.backend.dto.design.codegen.CodegenApplySelection;
import com.myide.backend.dto.design.codegen.CodegenFileStatus;
import com.myide.backend.dto.design.codegen.CodegenFileView;
import com.myide.backend.dto.design.codegen.CodegenPreviewRequest;
import com.myide.backend.dto.design.codegen.CodegenPreviewResponse;
import com.myide.backend.dto.design.codegen.CodegenTargetsResponse;
import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.service.FileService;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import com.myide.backend.service.design.doctor.Finding;
import com.myide.backend.service.design.doctor.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설계에서 코드를 만들고, 고른 것만 프로젝트에 쓴다.
 *
 * 두 가지를 지킨다.
 *
 * 하나, <b>설계에 오류가 있으면 아무것도 만들지 않는다.</b> 깨진 설계에서
 * 나온 코드는 고치는 데 더 오래 걸린다.
 *
 * 둘, <b>파일을 직접 쓰지 않고 FileService 를 거친다.</b> 경로 탈출 방어와
 * 코드맵 캐시 무효화가 거기에 이미 들어 있다. 직접 쓰면 코드맵이 낡은 채로
 * 남아 IDE 의 다른 기능이 조용히 어긋난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DesignCodegenService {

    private final List<DesignCodeGenerator> generators;
    private final DesignDoctorService doctorService;
    private final ProjectStackDetector stackDetector;
    private final FileService fileService;

    /**
     * 고를 수 있는 작업 폴더와, 고른 곳이 무엇인지 알려 준다.
     *
     * 미리보기를 누르기 전에 "여기는 Spring Boot 이고 패키지는 이것"이 보여야
     * 한다. 패키지를 잘못 짚으면 자바 파일이 한 개도 컴파일되지 않는데,
     * 그 사실을 코드가 다 만들어진 뒤에 알면 늦다.
     */
    public CodegenTargetsResponse targets(String workspaceId, String projectName,
                                          String branchName) {
        List<String> branches = stackDetector.listBranches(workspaceId, projectName);

        String branch = branchName == null || branchName.isBlank()
                ? branches.stream().findFirst().orElse("master")
                : branchName;

        ProjectStackDetector.Detected detected =
                stackDetector.detect(workspaceId, projectName, branch);

        return new CodegenTargetsResponse(branches, detected.stack().name(),
                detected.stack().label(), detected.basePackage(), detected.note());
    }

    public CodegenPreviewResponse preview(String workspaceId, CodegenPreviewRequest request) {
        DesignModelV2 model = requireModel(request.model());
        ProjectStackDetector.Detected detected =
                stackDetector.detect(workspaceId, request.projectName(), request.branchName());

        List<Finding> blocking = blockingFindings(model);

        if (!blocking.isEmpty()) {
            return new CodegenPreviewResponse(
                    detected.stack().name(), detected.stack().label(),
                    detected.basePackage(),
                    "설계에 먼저 고쳐야 할 문제가 " + blocking.size() + "건 있습니다.",
                    List.of(), blocking);
        }

        List<CodegenTarget> targets = targetsFor(detected.stack());

        if (targets.isEmpty()) {
            return new CodegenPreviewResponse(
                    detected.stack().name(), detected.stack().label(),
                    detected.basePackage(), unsupportedNote(detected),
                    List.of(), List.of());
        }

        CodegenOptions options = optionsFor(request.basePackage(), detected);
        List<GeneratedFile> files = generate(model, options, targets);
        List<CodegenFileView> views = new ArrayList<>();

        for (GeneratedFile file : files) {
            String existing = readExisting(workspaceId, request.projectName(),
                    request.branchName(), file.path());

            CodegenFileStatus status = existing == null
                    ? CodegenFileStatus.NEW
                    : existing.equals(file.content())
                    ? CodegenFileStatus.IDENTICAL
                    : CodegenFileStatus.CONFLICT;

            views.add(new CodegenFileView(
                    file.path(), file.content(), status,
                    hash(existing), existing == null ? "" : existing,
                    file.target().name(), file.target().label(), file.sourceLabel(),
                    file.requirementIds(), file.requirementLabels(), file.needsHandWork()));
        }

        return new CodegenPreviewResponse(
                detected.stack().name(), detected.stack().label(),
                options.basePackage(), detected.note(), views, List.of());
    }

    public CodegenApplyResponse apply(String workspaceId, CodegenApplyRequest request) {
        DesignModelV2 model = requireModel(request.model());
        List<Finding> blocking = blockingFindings(model);

        if (!blocking.isEmpty()) {
            // 미리보기에서 막았더라도 여기서 한 번 더 막는다. 미리보기와 적용
            // 사이에 설계가 바뀌었을 수 있다.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "설계에 오류가 " + blocking.size() + "건 있어 코드를 만들 수 없습니다.");
        }

        ProjectStackDetector.Detected detected =
                stackDetector.detect(workspaceId, request.projectName(), request.branchName());
        List<CodegenTarget> targets = targetsFor(detected.stack());

        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unsupportedNote(detected));
        }

        CodegenOptions options = optionsFor(request.basePackage(), detected);

        // 클라이언트가 보낸 내용을 쓰지 않고 같은 설계로 다시 만든다.
        Map<String, GeneratedFile> byPath = new LinkedHashMap<>();
        generate(model, options, targets).forEach(file -> byPath.put(file.path(), file));

        List<CodegenApplyResult> results = new ArrayList<>();
        int written = 0;
        int skipped = 0;
        int failed = 0;

        for (CodegenApplySelection selection : request.files()) {
            GeneratedFile file = byPath.get(selection.path());

            if (file == null) {
                results.add(new CodegenApplyResult(selection.path(),
                        CodegenApplyResult.Status.FAILED,
                        "이 파일은 지금 설계에서 만들어지지 않습니다. 미리보기를 다시 열어 주세요."));
                failed++;
                continue;
            }

            String existing = readExisting(workspaceId, request.projectName(),
                    request.branchName(), file.path());

            if (!hash(existing).equals(selection.expectedExistingHash() == null
                    ? "" : selection.expectedExistingHash())) {
                results.add(new CodegenApplyResult(file.path(),
                        CodegenApplyResult.Status.CHANGED_MEANWHILE,
                        "미리보기 이후에 이 파일이 바뀌어서 건드리지 않았습니다."));
                failed++;
                continue;
            }

            if (file.content().equals(existing)) {
                results.add(new CodegenApplyResult(file.path(),
                        CodegenApplyResult.Status.SKIPPED, "내용이 이미 같습니다."));
                skipped++;
                continue;
            }

            try {
                fileService.saveFile(FileRequest.builder()
                        .workspaceId(workspaceId)
                        .projectName(request.projectName())
                        .branchName(request.branchName())
                        .filePath(file.path())
                        .code(file.content())
                        .build());

                results.add(new CodegenApplyResult(file.path(),
                        CodegenApplyResult.Status.WRITTEN, ""));
                written++;
            } catch (Exception e) {
                log.warn("⚠️ [코드생성] 파일 쓰기 실패 {}: {}", file.path(), e.getMessage());
                results.add(new CodegenApplyResult(file.path(),
                        CodegenApplyResult.Status.FAILED,
                        e.getMessage() == null ? "쓰지 못했습니다." : e.getMessage()));
                failed++;
            }
        }

        return new CodegenApplyResponse(results, written, skipped, failed);
    }

    // ── 내부 ────────────────────────────────────────────────────────

    private DesignModelV2 requireModel(DesignModelV2 model) {
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설계 내용이 없습니다.");
        }
        return model;
    }

    private List<Finding> blockingFindings(DesignModelV2 model) {
        return doctorService.inspect(model).findings().stream()
                .filter(finding -> finding.severity() == Severity.ERROR)
                .toList();
    }

    private CodegenOptions optionsFor(String requested, ProjectStackDetector.Detected detected) {
        String basePackage = requested == null || requested.isBlank()
                ? detected.basePackage()
                : requested;

        return new CodegenOptions(basePackage, detected.stack());
    }

    private List<CodegenTarget> targetsFor(ProjectStack stack) {
        if (stack == ProjectStack.SPRING || stack == ProjectStack.REACT) {
            return Arrays.stream(CodegenTarget.values())
                    .filter(target -> target.stack() == stack)
                    .toList();
        }

        return List.of();
    }

    private String unsupportedNote(ProjectStackDetector.Detected detected) {
        if (detected.stack() == ProjectStack.NEXT) {
            return "Next.js 프로젝트에는 아직 코드를 만들지 못합니다. "
                    + "라우팅 구조가 달라서 그대로 넣으면 동작하지 않습니다.";
        }

        return detected.note();
    }

    private List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options,
                                         List<CodegenTarget> targets) {
        List<GeneratedFile> files = new ArrayList<>();

        for (CodegenTarget target : targets) {
            for (DesignCodeGenerator generator : generators) {
                if (generator.supports(target)) {
                    files.addAll(generator.generate(model, options));
                }
            }
        }

        return files;
    }

    /** 없는 파일이면 null. FileService 를 거쳐야 경로 방어를 그대로 받는다. */
    private String readExisting(String workspaceId, String projectName, String branchName,
                                String path) {
        try {
            return fileService.getFileContent(workspaceId, projectName, branchName, path);
        } catch (ResponseStatusException e) {
            return null;
        } catch (Exception e) {
            log.debug("기존 파일을 읽지 못했습니다 {}: {}", path, e.getMessage());
            return null;
        }
    }

    /** 없는 파일은 빈 문자열. 그래야 "없던 자리에 생겼다"도 충돌로 잡힌다. */
    private String hash(String content) {
        if (content == null) {
            return "";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }

            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("해시를 만들지 못했습니다.", e);
        }
    }
}
