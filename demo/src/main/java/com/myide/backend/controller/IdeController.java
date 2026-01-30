package com.myide.backend.controller;

import com.myide.backend.dto.FileNode;
import com.myide.backend.dto.ProjectRequest;
import com.myide.backend.service.FileSystemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IdeController {

    private final FileSystemService fileSystemService;

    // 1. 프로젝트 생성 (POST /api/projects)
    @PostMapping
    public ResponseEntity<String> createProject(@RequestBody @Valid ProjectRequest request) {
        log.info("프로젝트 생성 요청: User={}, Project={}, Lang={}",
                request.getUserId(), request.getProjectName(), request.getLanguage());
        fileSystemService.createProject(request);
        return ResponseEntity.ok("프로젝트가 성공적으로 생성되었습니다.");
    }

    // 2. 새 파일 생성 (POST /api/projects/files)
    @PostMapping("/files")
    public ResponseEntity<String> createFile(@RequestBody @Valid ProjectRequest request) {
        log.info("파일 생성 요청: Project={}, Path={}", request.getProjectName(), request.getFilePath());
        // 서비스 내부에서 폴더가 없으면 자동으로 만들어줍니다.
        fileSystemService.createFile(request);
        return ResponseEntity.ok("파일이 생성되었습니다.");
    }

    // 3. 파일 저장 (POST /api/projects/save)
    @PostMapping("/save")
    public ResponseEntity<String> saveFile(@RequestBody @Valid ProjectRequest request) {
        log.info("파일 저장 요청: Path={}", request.getFilePath());
        fileSystemService.saveFile(request);
        return ResponseEntity.ok("파일이 저장되었습니다.");
    }



    /**
     * 4. 프로젝트 파일 트리 조회
     * GET /api/projects/{userId}/{projectName}/files
     */
    @GetMapping("/{userId}/{projectName}/files")
    public ResponseEntity<FileNode> getFileTree(
            @PathVariable String userId,
            @PathVariable String projectName) {
        FileNode fileTree = fileSystemService.getFileTree(userId, projectName);
        return ResponseEntity.ok(fileTree);
    }

    /**
     * 5. 특정 파일 내용 조회
     * GET /api/projects/{userId}/{projectName}/file?path=src/Main.java
     */
    @GetMapping("/{userId}/{projectName}/file")
    public ResponseEntity<String> getFileContent(
            @PathVariable String userId,
            @PathVariable String projectName,
            @RequestParam String path) {
        String content = fileSystemService.getFileContent(userId, projectName, path);
        return ResponseEntity.ok(content);
    }
}