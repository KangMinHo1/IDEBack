package com.myide.backend.controller;

import com.myide.backend.dto.ide.BuildRequest;
import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.dto.project.CreateProjectRequest;
import com.myide.backend.service.BuildService;
import com.myide.backend.service.FileService;
import com.myide.backend.service.ProjectService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true", exposedHeaders = "Content-Disposition")
public class IdeController {

    private final ProjectService projectService;
    private final FileService fileService;
    private final BuildService buildService;

    @GetMapping("/{workspaceId}/projects")
    public ResponseEntity<FileNode> getProjects(@PathVariable String workspaceId) {
        return ResponseEntity.ok(projectService.getProjectList(workspaceId));
    }

    @GetMapping("/{workspaceId}/files")
    public ResponseEntity<FileNode> getFiles(@PathVariable String workspaceId, @RequestParam String projectName, @RequestParam(required = false, defaultValue = "main-repo") String branchName) {
        return ResponseEntity.ok(fileService.getFileTree(workspaceId, projectName, branchName));
    }

    @GetMapping("/{workspaceId}/file")
    public ResponseEntity<String> getFile(@PathVariable String workspaceId, @RequestParam String projectName, @RequestParam(required = false, defaultValue = "main-repo") String branchName, @RequestParam String path) {
        return ResponseEntity.ok(fileService.getFileContent(workspaceId, projectName, branchName, path));
    }

    @PostMapping("/project")
    public ResponseEntity<String> createProject(@RequestBody @Valid CreateProjectRequest request) {
        projectService.createNewProject(request);
        return ResponseEntity.ok("프로젝트 생성됨");
    }

    @PostMapping("/files")
    public ResponseEntity<String> createFile(@RequestBody @Valid FileRequest request) {
        fileService.createFile(request);
        return ResponseEntity.ok("생성됨");
    }

    @PostMapping("/save")
    public ResponseEntity<String> saveFile(@RequestBody @Valid FileRequest request) {
        fileService.saveFile(request);
        return ResponseEntity.ok("저장됨");
    }

    @DeleteMapping("/files")
    public ResponseEntity<String> deleteFile(@RequestBody @Valid FileRequest request) {
        fileService.deleteFile(request);
        return ResponseEntity.ok("삭제됨");
    }

    @PutMapping("/files/rename")
    public ResponseEntity<String> renameFile(@RequestBody @Valid FileRequest request) {
        fileService.renameFile(request);
        return ResponseEntity.ok("변경됨");
    }

    @PostMapping("/build")
    public ResponseEntity<Resource> buildProject(@RequestBody BuildRequest request) {
        try {
            String builtFilePath = buildService.buildProject(request);
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