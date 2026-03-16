package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.ide.BuildRequest;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class BuildService {

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final DockerService dockerService;

    public String buildProject(BuildRequest request) {
        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();
        Project project = projectRepository.findAll().stream()
                .filter(p -> p.getWorkspace().getUuid().equals(request.getWorkspaceId()) && p.getName().equals(request.getProjectName()))
                .findFirst().orElseThrow();

        LanguageType lang = project.getLanguage();
        if (lang.getBuildCommand() == null || lang.getOutputFileName() == null) {
            throw new RuntimeException(lang.name() + " 언어는 빌드가 필요 없습니다.");
        }

        String projectName = request.getProjectName();
        String branchFolder = (request.getBranchName() == null || request.getBranchName().isEmpty() || "main".equals(request.getBranchName()))
                ? "main-repo" : request.getBranchName();

        String outputFileName = lang.getOutputFileName().replace("{project}", projectName);
        String rawBuildCmd = lang.getBuildCommand().replace("{output}", outputFileName);

        // 💡 [핵심 수정] cd 이동 경로에 쌍따옴표(\" \")를 씌워서 띄어쓰기 폴더명 완벽 방어!
        String cmd = String.format("cd \"%s/%s\" && %s", projectName, branchFolder, rawBuildCmd);
        String containerFilePath = "/app/" + projectName + "/" + branchFolder + "/" + outputFileName;

        Path tempDir = Paths.get(workspace.getPath(), ".temp");
        try { if (!Files.exists(tempDir)) Files.createDirectories(tempDir); } catch(IOException e){}
        String hostFilePath = tempDir.resolve(outputFileName).toString();

        dockerService.buildAndCopy(request.getWorkspaceId(), cmd, containerFilePath, hostFilePath);
        return hostFilePath;
    }
}