package com.myide.backend.service;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.dto.FileNode;
import com.myide.backend.dto.ProjectRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class FileSystemService {

    // 윈도우 경로 주의 (C:\\ide_projects)
    private static final String ROOT_PATH = "C:\\ide_projects";

    // --- [생성 기능] ---
    public void createProject(ProjectRequest request) {
        Path projectPath = Paths.get(ROOT_PATH, request.getUserId(), request.getProjectName());
        validatePath(projectPath);

        try {
            if (Files.exists(projectPath)) {
                throw new RuntimeException("이미 존재하는 프로젝트입니다.");
            }
            Files.createDirectories(projectPath);

            LanguageType lang = request.getLanguage();

            // 1. 메인 소스 파일 생성 (Program.cs 등)
            String fileName = lang.getMainFileName();
            Path filePath = projectPath.resolve(fileName);
            Files.write(filePath, lang.getDefaultCode().getBytes(StandardCharsets.UTF_8));

            log.info("메인 파일 생성 완료: {}", filePath);

            // [핵심 수정] C# (.NET) 프로젝트인 경우 .csproj 파일 필수 생성
            if (lang == LanguageType.CSHARP) {
                createCsharpProjectFile(projectPath, request.getProjectName());
            }

        } catch (IOException e) {
            log.error("프로젝트 생성 실패", e);
            throw new RuntimeException("프로젝트 생성 중 오류가 발생했습니다.");
        }
    }

    // [Helper] C# 프로젝트 설정 파일 (.csproj) 생성 메서드
    private void createCsharpProjectFile(Path projectPath, String projectName) throws IOException {
        // [버전 업그레이드] net6.0 -> net8.0 (도커 환경에 맞춰 수정)
        String csprojContent =
                "<Project Sdk=\"Microsoft.NET.Sdk\">\n" +
                        "  <PropertyGroup>\n" +
                        "    <OutputType>Exe</OutputType>\n" +
                        "    <TargetFramework>net8.0</TargetFramework>\n" +
                        "    <ImplicitUsings>enable</ImplicitUsings>\n" +
                        "    <Nullable>enable</Nullable>\n" +
                        "  </PropertyGroup>\n" +
                        "</Project>";

        // 프로젝트명.csproj 로 저장
        Path csprojPath = projectPath.resolve(projectName + ".csproj");
        Files.write(csprojPath, csprojContent.getBytes(StandardCharsets.UTF_8));

        log.info("C# 프로젝트 파일 생성 완료: {}", csprojPath);
    }

    public void createFile(ProjectRequest request) {
        Path projectPath = Paths.get(ROOT_PATH, request.getUserId(), request.getProjectName());
        Path filePath = projectPath.resolve(request.getFilePath());
        validatePath(filePath);

        try {
            if (Files.exists(filePath)) {
                throw new RuntimeException("이미 존재하는 파일입니다.");
            }

            // 상위 폴더가 없으면 생성
            Files.createDirectories(filePath.getParent());

            // 빈 파일 생성 (내용 없이)
            Files.createFile(filePath);

            // 만약 초기 코드가 있다면 작성
            if (request.getCode() != null && !request.getCode().isEmpty()) {
                Files.write(filePath, request.getCode().getBytes(StandardCharsets.UTF_8));
            }

        } catch (IOException e) {
            throw new RuntimeException("파일 생성 실패: " + e.getMessage());
        }
    }

    // --- [저장/조회 기능] ---
    public void saveFile(ProjectRequest request) {
        Path filePath = Paths.get(ROOT_PATH, request.getUserId(), request.getProjectName(), request.getFilePath());
        validatePath(filePath);

        try {
            // 상위 디렉토리 존재 확인
            if (!Files.exists(filePath.getParent())) {
                Files.createDirectories(filePath.getParent());
            }

            Files.write(filePath, request.getCode().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패");
        }
    }

    public FileNode getFileTree(String userId, String projectName) {
        Path projectPath = Paths.get(ROOT_PATH, userId, projectName);
        validatePath(projectPath);

        if (!Files.exists(projectPath)) {
            throw new RuntimeException("프로젝트를 찾을 수 없습니다.");
        }

        return traverseDirectory(projectPath, projectPath);
    }

    public String getFileContent(String userId, String projectName, String relativePath) {
        Path filePath = Paths.get(ROOT_PATH, userId, projectName, relativePath);
        validatePath(filePath);

        try {
            if (!Files.exists(filePath)) return "";
            return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("파일 읽기 실패");
        }
    }

    // --- [유틸] ---
    private FileNode traverseDirectory(Path rootPath, Path currentPath) {
        String name = currentPath.getFileName().toString();
        String relativePath = rootPath.relativize(currentPath).toString().replace("\\", "/");

        if (!Files.isDirectory(currentPath)) {
            return FileNode.builder().name(name).type("FILE").path(relativePath).children(null).build();
        }

        List<FileNode> children = Collections.emptyList();
        try (Stream<Path> stream = Files.list(currentPath)) {
            children = stream.map(child -> traverseDirectory(rootPath, child))
                    .sorted((a, b) -> {
                        if (a.getType().equals(b.getType())) return a.getName().compareTo(b.getName());
                        return "DIRECTORY".equals(a.getType()) ? -1 : 1;
                    }).collect(Collectors.toList());
        } catch (IOException e) { /* Log */ }

        return FileNode.builder().name(name).type("DIRECTORY").path(relativePath).children(children).build();
    }

    private void validatePath(Path path) {
        try {
            Path realPath = path.toAbsolutePath().normalize();
            Path root = Paths.get(ROOT_PATH).toAbsolutePath().normalize();
            if (!realPath.startsWith(root)) throw new SecurityException("유효하지 않은 경로");
        } catch (Exception e) {
            throw new SecurityException("경로 검증 실패");
        }
    }
}