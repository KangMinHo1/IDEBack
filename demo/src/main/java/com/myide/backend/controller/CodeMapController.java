package com.myide.backend.controller;

import com.myide.backend.dto.codemap.CreateComponentRequest;
import com.myide.backend.dto.codemap.CreateRelationRequest;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.dto.codemap.CodeGenerateRequest; // 💡 새로 추가된 DTO 임포트
import com.myide.backend.service.CodeMapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/codemap")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CodeMapController {

    private final CodeMapService codeMapService;

    @GetMapping("/analyze")
    public ResponseEntity<CodeMapResponse> analyzeCodeMap(
            @RequestParam String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName,
            @RequestParam(required = false, defaultValue = "JAVA") String language) {

        CodeMapResponse result = codeMapService.getAnalyzedCodeMap(workspaceId, projectName, branchName);
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

    // 💡 [NEW] 클래스 내부에 변수/메서드 생성 API
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