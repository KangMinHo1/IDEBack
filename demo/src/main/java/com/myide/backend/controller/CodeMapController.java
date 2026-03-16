package com.myide.backend.controller;

import com.myide.backend.dto.codemap.CreateComponentRequest;
import com.myide.backend.dto.codemap.CreateRelationRequest;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.service.CodeMapService;
import jakarta.validation.Valid; // 💡 임포트 필수!
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
    // 💡 @Valid 추가
    public ResponseEntity<String> createComponent(@RequestBody @Valid CreateComponentRequest request) {
        try {
            codeMapService.createComponent(request);
            return ResponseEntity.ok("컴포넌트가 성공적으로 생성되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/relations")
    // 💡 @Valid 추가
    public ResponseEntity<String> createRelation(@RequestBody @Valid CreateRelationRequest request) {
        try {
            codeMapService.createRelation(request);
            return ResponseEntity.ok("관계 코드가 성공적으로 주입되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("관계 주입 실패: " + e.getMessage());
        }
    }

    @DeleteMapping("/relations")
    // 💡 @Valid 추가
    public ResponseEntity<String> deleteRelation(@RequestBody @Valid CreateRelationRequest request) {
        try {
            codeMapService.deleteRelation(request);
            return ResponseEntity.ok("관계가 성공적으로 삭제되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("관계 삭제 실패: " + e.getMessage());
        }
    }
}