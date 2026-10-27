package com.myide.backend.controller;

import com.myide.backend.dto.ide.BuildRequest;
import com.myide.backend.dto.ide.FileNode;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.dto.project.CreateProjectRequest;
import com.myide.backend.handler.CollaborationWebSocketHandler;
import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
import com.myide.backend.security.CollabRoomName;
import com.myide.backend.service.BuildService;
import com.myide.backend.service.FileService;
import com.myide.backend.service.ProjectService;
import com.myide.backend.service.saferun.AuthorStatusService;
import com.myide.backend.service.saferun.BranchKey;
import com.myide.backend.service.saferun.EditLedger;
import com.myide.backend.service.sandbox.SandboxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class IdeController {

    private final ProjectService projectService;
    private final FileService fileService;
    private final BuildService buildService;

    // 파일 트리 변경 이벤트 WebSocket broadcast 용도
    private final WorkspaceEventWebSocketHandler workspaceEventWebSocketHandler;

    // 안전 실행: 누가 무엇을 고쳤는지 적고, 저장된 파일의 문법을 검사한다
    private final AuthorStatusService authorStatusService;

    // 파일을 지우거나 이름을 바꾸면 그 파일의 동시편집 문서도 버려야 한다
    private final CollaborationWebSocketHandler collaborationHandler;

    // 팀 파일이 바뀌면, 그 파일을 샌드박스에서 고치는 사람에게 알린다
    private final SandboxService sandboxService;

    @GetMapping("/{workspaceId}/projects")
    public ResponseEntity<FileNode> getProjects(@PathVariable String workspaceId) {
        return ResponseEntity.ok(projectService.getProjectList(workspaceId));
    }

    @GetMapping("/{workspaceId}/files")
    public ResponseEntity<FileNode> getFiles(
            @PathVariable String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName
    ) {
        return ResponseEntity.ok(fileService.getFileTree(workspaceId, projectName, branchName));
    }

    @GetMapping("/{workspaceId}/file")
    public ResponseEntity<String> getFile(
            @PathVariable String workspaceId,
            @RequestParam String projectName,
            @RequestParam(required = false, defaultValue = "master") String branchName,
            @RequestParam String path
    ) {
        return ResponseEntity.ok(fileService.getFileContent(workspaceId, projectName, branchName, path));
    }

    @PostMapping("/project")
    public ResponseEntity<String> createProject(@RequestBody @Valid CreateProjectRequest request) {
        projectService.createNewProject(request);
        return ResponseEntity.ok("프로젝트 생성됨");
    }

    @PostMapping("/files")
    public ResponseEntity<String> createFile(
            @RequestBody @Valid FileRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        fileService.createFile(request);

        // 파일 생성 후 같은 workspace/project/branch 방에 접속 중인 사용자들에게 알림
        workspaceEventWebSocketHandler.broadcastFileTreeChanged(request, "CREATE");

        authorStatusService.recordEdit(branchKeyOf(request), request.getFilePath(), userId);

        return ResponseEntity.ok("생성됨");
    }

    @PostMapping("/save")
    public ResponseEntity<String> saveFile(@RequestBody @Valid FileRequest request) {
        // 저장한 사람을 작성자로 적지 않는다. 팀 모드의 자동 저장은 고친 사람이 아니라
        // "저장 담당자"가 보내기 때문이다. 작성자는 동시 편집 메시지에서 따로 적는다.
        // 여기서는 방금 디스크에 들어간 내용의 문법만 검사한다.
        if (fileService.saveFile(request)) {
            authorStatusService.onFileSaved(branchKeyOf(request), request.getFilePath(), request.getCode());

            // 이 파일을 샌드박스에서 고치는 사람은 팀의 편집이 화면에 보이지 않는다.
            // 팀 파일이 바뀌었다는 사실만이라도 알려 준다.
            sandboxService.notifyTeamFileChanged(branchKeyOf(request), request.getFilePath());
        }

        return ResponseEntity.ok("저장됨");
    }

    @DeleteMapping("/files")
    public ResponseEntity<String> deleteFile(
            @RequestBody @Valid FileRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        fileService.deleteFile(request);

        // 파일 삭제 후 같은 workspace/project/branch 방에 접속 중인 사용자들에게 알림
        workspaceEventWebSocketHandler.broadcastFileTreeChanged(request, "DELETE");

        // 지운 파일의 동시편집 문서를 버린다. 남겨 두면 같은 이름으로 다시 만든 파일이
        // 옛 문서를 받는다. (열어 둔 사람의 자동 저장이 파일을 되살리는 것은
        // FileService.saveFile 이 막는다.)
        collaborationHandler.dropDocsUnder(roomOf(request));

        // 삭제도 "이 사람의 변경"이다. 이 사람이 실행에서 제외되면 지워진 파일이 되살아나야 한다.
        BranchKey key = branchKeyOf(request);
        authorStatusService.recordEdit(key, request.getFilePath(), userId);
        authorStatusService.onFileRemoved(key, request.getFilePath());

        // 지워진 파일(또는 폴더 아래 파일)을 샌드박스에서 고치던 사람에게 알린다.
        sandboxService.notifyTeamFileChanged(key, request.getFilePath());

        return ResponseEntity.ok("삭제됨");
    }

    @PutMapping("/files/rename")
    public ResponseEntity<String> renameFile(
            @RequestBody @Valid FileRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        String renamedPath = fileService.renameFile(request);

        // 파일명 변경 후 같은 workspace/project/branch 방에 접속 중인 사용자들에게 알림
        workspaceEventWebSocketHandler.broadcastFileTreeChanged(request, "RENAME");

        // 옛 경로의 동시편집 문서를 버린다(삭제와 같은 이유).
        collaborationHandler.dropDocsUnder(roomOf(request));

        // 이름 변경은 "옛 경로 삭제 + 새 경로 생성"으로 적는다.
        BranchKey key = branchKeyOf(request);
        // 이동(드래그)도 같은 길로 온다. 새 경로는 서비스가 실제로 옮긴 곳을 쓴다.
        String oldPath = EditLedger.normalizePath(request.getFilePath());

        authorStatusService.recordEdit(key, oldPath, userId);
        authorStatusService.onFileRemoved(key, oldPath);
        authorStatusService.recordEdit(key, renamedPath, userId);

        // 옛 이름의 파일을 샌드박스에서 고치던 사람에게 알린다(팀 쪽에서는 그 파일이 사라졌다).
        sandboxService.notifyTeamFileChanged(key, oldPath);

        return ResponseEntity.ok("변경됨");
    }

    private BranchKey branchKeyOf(FileRequest request) {
        return BranchKey.of(request.getWorkspaceId(), request.getProjectName(), request.getBranchName());
    }

    /** 이 파일을 팀이 함께 여는 동시편집 방의 이름. */
    private String roomOf(FileRequest request) {
        BranchKey key = branchKeyOf(request);

        return CollabRoomName.fileRoom(
                key.workspaceId(),
                key.projectName(),
                key.branchName(),
                EditLedger.normalizePath(request.getFilePath())
        );
    }

    @PostMapping("/build")
    public ResponseEntity<Resource> buildProject(@RequestBody BuildRequest request) {
        try {
            String builtFilePath = buildService.buildProject(request);
            Path path = Paths.get(builtFilePath);
            Resource resource = new UrlResource(path.toUri());

            if (!resource.exists()) {
                throw new RuntimeException("빌드 파일 없음");
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + resource.getFilename() + "\""
                    )
                    .body(resource);

        } catch (MalformedURLException e) {
            throw new RuntimeException("경로 오류", e);
        }
    }
}