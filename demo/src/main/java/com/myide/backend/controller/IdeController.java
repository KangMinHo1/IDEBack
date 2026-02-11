package com.myide.backend.controller;

import com.myide.backend.dto.*;
import com.myide.backend.service.FileSystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class IdeController {

    private final FileSystemService fileSystemService;

    // 0. 워크스페이스 내 프로젝트 목록 조회
    @GetMapping("/{workspaceId}/projects")
    public ResponseEntity<FileNode> getProjects(@PathVariable String workspaceId) {
        return ResponseEntity.ok(fileSystemService.getProjectList(workspaceId));
    }

    // 1. 파일 트리 조회
    @GetMapping("/{workspaceId}/files")
    public ResponseEntity<FileNode> getFiles(
            @PathVariable String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "main-repo") String branchName
    ) {
        return ResponseEntity.ok(fileSystemService.getFileTree(workspaceId, projectName, branchName));
    }

    // 2. 파일 내용 조회
    @GetMapping("/{workspaceId}/file")
    public ResponseEntity<String> getFile(
            @PathVariable String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "main-repo") String branchName,
            @RequestParam String path
    ) {
        return ResponseEntity.ok(fileSystemService.getFileContent(workspaceId, projectName, branchName, path));
    }

    // 3. 프로젝트 생성
    @PostMapping("/project")
    public ResponseEntity<String> createProject(@RequestBody CreateProjectRequest request) {
        fileSystemService.createNewProject(request);
        return ResponseEntity.ok("프로젝트 생성됨");
    }

    // [New] Git URL 업데이트 (연동하기)
    // URL: POST /api/workspaces/project/git-url
    @PostMapping("/project/git-url")
    public ResponseEntity<String> updateGitUrl(@RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        String projectName = body.get("projectName");
        String gitUrl = body.get("gitUrl");

        fileSystemService.updateProjectGitUrl(workspaceId, projectName, gitUrl);
        return ResponseEntity.ok("Git URL 업데이트 완료");
    }

    // 4. 브랜치 생성
    @PostMapping("/branches")
    public ResponseEntity<String> createBranch(@RequestBody FileRequest request) {
        fileSystemService.createBranch(request);
        return ResponseEntity.ok("브랜치 생성됨");
    }

    // 5. 파일 생성
    @PostMapping("/files")
    public ResponseEntity<String> createFile(@RequestBody FileRequest request) {
        fileSystemService.createFile(request);
        return ResponseEntity.ok("생성됨");
    }

    // 6. 파일 저장
    @PostMapping("/save")
    public ResponseEntity<String> saveFile(@RequestBody FileRequest request) {
        fileSystemService.saveFile(request);
        return ResponseEntity.ok("저장됨");
    }

    // 7. 파일 삭제
    @DeleteMapping("/files")
    public ResponseEntity<String> deleteFile(@RequestBody FileRequest request) {
        fileSystemService.deleteFile(request);
        return ResponseEntity.ok("삭제됨");
    }

    // 8. 이름 변경
    @PutMapping("/files/rename")
    public ResponseEntity<String> renameFile(@RequestBody FileRequest request) {
        fileSystemService.renameFile(request);
        return ResponseEntity.ok("변경됨");
    }

    // 9. 빌드
    @PostMapping("/build")
    public ResponseEntity<Resource> buildProject(@RequestBody BuildRequest request) {
        try {
            String builtFilePath = fileSystemService.buildProject(request);
            Path path = Paths.get(builtFilePath);
            Resource resource = new UrlResource(path.toUri());

            if (!resource.exists()) throw new RuntimeException("빌드 파일 없음");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) { throw new RuntimeException("경로 오류"); }
    }
}