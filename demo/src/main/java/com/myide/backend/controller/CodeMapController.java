package com.myide.backend.controller;

import com.myide.backend.dto.codemap.CreateComponentRequest;
import com.myide.backend.dto.codemap.CreateRelationRequest;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.dto.codemap.CodeGenerateRequest;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
import com.myide.backend.service.CodeMapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/codemap")
@RequiredArgsConstructor
public class CodeMapController {

    private final CodeMapService codeMapService;
    private final WorkspaceEventWebSocketHandler workspaceEventWebSocketHandler;

    @GetMapping("/analyze")
    public ResponseEntity<CodeMapResponse> analyzeCodeMap(
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName,
            @RequestParam(required = false, defaultValue = "JAVA") String language) {

        CodeMapResponse result = codeMapService.getAnalyzedCodeMap(workspaceId, projectName, branchName, language);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/summary")
    public ResponseEntity<String> getSummary(
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName,
            @RequestParam String filePath) {
        return ResponseEntity.ok(codeMapService.getOrGenerateSummary(workspaceId, projectName, branchName, filePath));
    }

    @PostMapping("/components")
    public ResponseEntity<String> createComponent(@RequestBody @Valid CreateComponentRequest request) {
        try {
            codeMapService.createComponent(request);
            // 탐색기 파일 생성과 같은 알림을 보낸다. 없으면 팀원 탐색기가 새 파일을 모른다.
            workspaceEventWebSocketHandler.broadcastFileTreeChanged(FileRequest.builder()
                    .workspaceId(request.getWorkspaceId())
                    .projectName(request.getProjectName())
                    .branchName(request.getBranchName())
                    .filePath(request.getName())
                    .type("file")
                    .build(), "CREATE");
            return ResponseEntity.ok("컴포넌트가 성공적으로 생성되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/relations")
    public ResponseEntity<String> createRelation(@RequestBody @Valid CreateRelationRequest request) {
        try {
            codeMapService.createRelation(request);
            return ResponseEntity.ok("관계 코드가 성공적으로 주입되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("관계 주입 실패: " + e.getMessage());
        }
    }

    @DeleteMapping("/relations")
    public ResponseEntity<String> deleteRelation(@RequestBody @Valid CreateRelationRequest request) {
        try {
            codeMapService.deleteRelation(request);
            return ResponseEntity.ok("관계가 성공적으로 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("관계 삭제 실패: " + e.getMessage());
        }
    }

    @PostMapping("/generate")
    public ResponseEntity<String> generateCodeComponent(@RequestBody @Valid CodeGenerateRequest request) {
        try {
            codeMapService.generateCodeComponent(request);
            return ResponseEntity.ok("코드가 성공적으로 생성되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("코드 생성 실패: " + e.getMessage());
        }
    }
}