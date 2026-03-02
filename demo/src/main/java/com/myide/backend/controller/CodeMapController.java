package com.myide.backend.controller;

import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.service.CodeMapService;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.analyzer.CodeAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/codemap")
@RequiredArgsConstructor // 💡 @Autowired 대신 생성자 주입을 사용해 깔끔하게 관리합니다.
public class CodeMapController {

    private final List<CodeAnalyzer> analyzers;
    private final WorkspaceService workspaceService;
    private final CodeMapService codeMapService; // 💡 방금 만든 AI 요약 서비스 추가

    // 🗺️ 1. 코드맵 노드/엣지(화살표) 데이터를 그려주는 기존 API
    @GetMapping("/analyze")
    public ResponseEntity<CodeMapResponse> getCodeMap(
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(defaultValue = "master") String branchName,
            @RequestParam String language) {

        Path projectPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        if (!projectPath.toFile().exists()) {
            throw new RuntimeException("프로젝트 폴더를 찾을 수 없습니다. 실제 컴퓨터 경로: " + projectPath.toAbsolutePath());
        }

        CodeAnalyzer targetAnalyzer = analyzers.stream()
                .filter(analyzer -> analyzer.supports(language))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 언어입니다: " + language));

        CodeMapResponse response = targetAnalyzer.analyze(projectPath.toString());
        return ResponseEntity.ok(response);
    }

    // 🤖 2. [신규] 특정 파일의 AI 요약을 가져오는 API (해시 캐싱 적용)
    @GetMapping("/summary")
    public ResponseEntity<String> getAiSummary(
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(defaultValue = "master") String branchName,
            @RequestParam String filePath) {

        // 캐싱 로직이 들어있는 서비스에게 모든 처리를 위임하고 결과만 받아서 리턴합니다.
        String summary = codeMapService.getOrGenerateSummary(workspaceId, projectName, branchName, filePath);

        return ResponseEntity.ok(summary);
    }
}