package com.myide.backend.controller;

import com.myide.backend.domain.Workspace;
import com.myide.backend.dto.FileNode;
import com.myide.backend.dto.WorkspaceRequest;
import com.myide.backend.service.FileSystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class IdeController {

    private final FileSystemService fileSystemService;


    // 3. 파일 트리 조회
    @GetMapping("/{workspaceId}/files")
    public ResponseEntity<FileNode> getFiles(@PathVariable String workspaceId) {
        return ResponseEntity.ok(fileSystemService.getFileTree(workspaceId));
    }

    // 4. 파일 내용 조회
    @GetMapping("/{workspaceId}/file")
    public ResponseEntity<String> getFile(@PathVariable String workspaceId, @RequestParam String path) {
        return ResponseEntity.ok(fileSystemService.getFileContent(workspaceId, path));
    }

    // 5. 프로젝트(폴더) 생성
    @PostMapping("/files/project")
    public ResponseEntity<String> createProject(@RequestBody WorkspaceRequest request) {
        fileSystemService.createNewProject(request);
        return ResponseEntity.ok("프로젝트 생성됨");
    }

    // 6. 파일/폴더 생성
    @PostMapping("/files")
    public ResponseEntity<String> createFile(@RequestBody WorkspaceRequest request) {
        fileSystemService.createFile(request);
        return ResponseEntity.ok("생성됨");
    }

    // 7. 파일 저장
    @PostMapping("/save")
    public ResponseEntity<String> saveFile(@RequestBody WorkspaceRequest request) {
        fileSystemService.saveFile(request);
        return ResponseEntity.ok("저장됨");
    }

    // 8. 파일 삭제
    @DeleteMapping("/files")
    public ResponseEntity<String> deleteFile(@RequestParam String workspaceId, @RequestParam String path) {
        WorkspaceRequest req = new WorkspaceRequest();
        req.setWorkspaceId(workspaceId);
        req.setFilePath(path);
        fileSystemService.deleteFile(req);
        return ResponseEntity.ok("삭제됨");
    }

    // 9. 이름 변경
    @PutMapping("/files/rename")
    public ResponseEntity<String> renameFile(@RequestBody WorkspaceRequest request, @RequestParam String newName) {
        fileSystemService.renameFile(request, newName);
        return ResponseEntity.ok("변경됨");
    }

    // 10. [신규] 프로젝트 빌드 및 다운로드
    @PostMapping("/build")
    public ResponseEntity<Resource> buildProject(@RequestBody WorkspaceRequest request) {
        try {
            // 1. 빌드 실행 후 생성된 파일 경로 받기
            String builtFilePath = fileSystemService.buildProject(request);

            // 2. 파일 리소스 로드
            Path path = Paths.get(builtFilePath);
            Resource resource = new UrlResource(path.toUri());

            if (!resource.exists()) {
                throw new RuntimeException("빌드 결과물을 찾을 수 없습니다.");
            }

            String downloadName = resource.getFilename(); // 예: my-java-app.jar

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                    .body(resource);

        } catch (MalformedURLException e) {
            throw new RuntimeException("파일 경로 오류: " + e.getMessage());
        }
    }
}