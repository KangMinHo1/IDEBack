package com.myide.backend.service;

import com.myide.backend.domain.Workspace;
import com.myide.backend.dto.FileNode;
import com.myide.backend.dto.WorkspaceRequest;
import com.myide.backend.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileSystemService {

    private static final String ROOT_PATH = "C:\\WebIDE\\workspaces";
    private final WorkspaceRepository workspaceRepository;

    // [중요] DockerService 주입
    private final DockerService dockerService;

    public Workspace createWorkspace(String userId, String name) {
        String uuid = UUID.randomUUID().toString();
        Path path = Paths.get(ROOT_PATH, uuid);
        try {
            Files.createDirectories(path);
            Workspace workspace = Workspace.builder().uuid(uuid).name(name).ownerId(userId).build();
            return workspaceRepository.save(workspace);
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public List<Workspace> getMyWorkspaces(String userId) {
        return workspaceRepository.findAll().stream()
                .filter(w -> w.getOwnerId().equals(userId))
                .collect(Collectors.toList());
    }

    public void createNewProject(WorkspaceRequest request) {
        Path projectPath = Paths.get(ROOT_PATH, request.getWorkspaceId(), request.getName());
        try {
            if (Files.exists(projectPath)) throw new RuntimeException("이미 존재하는 프로젝트 이름입니다.");
            Files.createDirectories(projectPath);

            String fileName;
            String content;
            String lang = request.getLanguage().toUpperCase();

            switch (lang) {
                case "JAVA":
                    fileName = "Main.java";
                    content = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello Java!\");\n    }\n}";
                    break;
                case "PYTHON":
                    fileName = "main.py";
                    content = "print('Hello Python!')";
                    break;
                case "C":
                    fileName = "main.c";
                    content = "#include <stdio.h>\n\nint main() {\n    printf(\"Hello C!\\n\");\n    return 0;\n}";
                    break;
                case "CPP":
                    fileName = "main.cpp";
                    content = "#include <iostream>\n\nint main() {\n    std::cout << \"Hello C++!\" << std::endl;\n    return 0;\n}";
                    break;
                case "CSHARP":
                    fileName = "Program.cs";
                    content = "using System;\n\nclass Program {\n    static void Main() {\n        Console.WriteLine(\"Hello C#!\");\n    }\n}";
                    String csprojContent = "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <OutputType>Exe</OutputType>\n    <TargetFramework>net8.0</TargetFramework>\n  </PropertyGroup>\n</Project>";
                    Files.writeString(projectPath.resolve(request.getName() + ".csproj"), csprojContent);
                    break;
                case "JAVASCRIPT":
                    fileName = "app.js";
                    content = "console.log('Hello JavaScript!');";
                    break;
                case "HTML":
                    fileName = "index.html";
                    content = "<!DOCTYPE html>\n<html>\n<body>\n    <h1>Hello HTML!</h1>\n</body>\n</html>";
                    break;
                default:
                    fileName = "README.txt";
                    content = "Project Created.";
            }
            Files.writeString(projectPath.resolve(fileName), content);

        } catch (IOException e) {
            throw new RuntimeException("프로젝트 생성 실패: " + e.getMessage());
        }
    }

    public void createFile(WorkspaceRequest request) {
        Path path = Paths.get(ROOT_PATH, request.getWorkspaceId(), request.getFilePath());
        try {
            if (Files.exists(path)) throw new RuntimeException("이미 존재하는 파일입니다.");

            // 폴더 생성 로직 추가
            if ("folder".equalsIgnoreCase(request.getType())) {
                Files.createDirectories(path);
            } else {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            }
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void saveFile(WorkspaceRequest request) {
        Path path = Paths.get(ROOT_PATH, request.getWorkspaceId(), request.getFilePath());
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, request.getCode(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            log.error("파일 저장 실패 (경로 없음): {}", path);
            throw new RuntimeException("파일 경로를 찾을 수 없습니다: " + request.getFilePath());
        } catch (IOException e) {
            log.error("파일 저장 실패", e);
            throw new RuntimeException("파일 저장 실패: " + e.getMessage());
        }
    }

    public FileNode getFileTree(String workspaceId) {
        Path root = Paths.get(ROOT_PATH, workspaceId);
        if (!Files.exists(root)) throw new RuntimeException("워크스페이스를 찾을 수 없습니다.");
        String workspaceName = workspaceRepository.findById(workspaceId).map(Workspace::getName).orElse("Unknown Workspace");
        return traverseDirectory(root, root, workspaceName);
    }

    private FileNode traverseDirectory(Path dir, Path rootDir, String rootName) {
        String relativePath = rootDir.relativize(dir).toString().replace("\\", "/");
        String displayName = relativePath.isEmpty() ? rootName : dir.getFileName().toString();
        String id = relativePath.isEmpty() ? "root" : relativePath;
        FileNode node = FileNode.builder().id(id).name(displayName).type("folder").build();
        try (Stream<Path> stream = Files.list(dir)) {
            List<FileNode> children = stream.map(path -> {
                if (Files.isDirectory(path)) return traverseDirectory(path, rootDir, null);
                else return FileNode.builder().id(rootDir.relativize(path).toString().replace("\\", "/")).name(path.getFileName().toString()).type("file").build();
            }).collect(Collectors.toList());
            node.setChildren(children);
        } catch (IOException e) { node.setChildren(Collections.emptyList()); }
        return node;
    }

    public String getFileContent(String workspaceId, String filePath) {
        Path path = Paths.get(ROOT_PATH, workspaceId, filePath);
        try { return Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void deleteFile(WorkspaceRequest request) {
        if ("root".equals(request.getFilePath())) { deleteWorkspace(request.getWorkspaceId()); return; }
        Path path = Paths.get(ROOT_PATH, request.getWorkspaceId(), request.getFilePath());
        try { FileSystemUtils.deleteRecursively(path); } catch (IOException e) { throw new RuntimeException(e); }
    }

    private void deleteWorkspace(String workspaceId) {
        Path path = Paths.get(ROOT_PATH, workspaceId);
        try { FileSystemUtils.deleteRecursively(path); workspaceRepository.deleteById(workspaceId); } catch (IOException e) { throw new RuntimeException(e); }
    }

    public void renameFile(WorkspaceRequest request, String newName) {
        if ("root".equals(request.getFilePath())) {
            Workspace ws = workspaceRepository.findById(request.getWorkspaceId()).orElseThrow();
            Workspace newWs = new Workspace(ws.getUuid(), newName, ws.getOwnerId(), ws.getDescription());
            workspaceRepository.save(newWs);
            return;
        }
        Path oldPath = Paths.get(ROOT_PATH, request.getWorkspaceId(), request.getFilePath());
        Path newPath = oldPath.getParent().resolve(newName);
        try { Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING); } catch (IOException e) { throw new RuntimeException(e); }
    }

    // [신규] 빌드 로직
    public String buildProject(WorkspaceRequest request) {
        String workspaceId = request.getWorkspaceId();
        String projectName = request.getName();
        String language = request.getLanguage();

        String cmd = "";
        String outputFileName = "";

        if ("JAVA".equalsIgnoreCase(language)) {
            outputFileName = projectName + ".jar";
            cmd = String.format(
                    "cd %s && javac -encoding UTF-8 *.java && jar cfe %s Main *.class",
                    projectName, outputFileName
            );
        } else {
            throw new IllegalArgumentException("현재는 Java 빌드만 지원합니다.");
        }

        // 결과 파일의 컨테이너 내부 경로 (예: /app/my-java-app/my-java-app.jar)
        String containerFilePath = "/app/" + projectName + "/" + outputFileName;

        // 호스트 임시 저장 경로
        Path tempDir = Paths.get(ROOT_PATH, "temp");
        try { if (!Files.exists(tempDir)) Files.createDirectories(tempDir); } catch(IOException e){}

        String hostFilePath = tempDir.resolve(outputFileName).toString();

        // [핵심] DockerService의 통합 메서드 호출
        dockerService.buildAndCopy(workspaceId, cmd, containerFilePath, hostFilePath);

        return hostFilePath;
    }
}