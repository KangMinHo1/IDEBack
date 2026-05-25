package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.myide.backend.domain.CodeMapCache;
import com.myide.backend.domain.CodeSummary;
import com.myide.backend.dto.codemap.CreateComponentRequest;
import com.myide.backend.dto.codemap.CreateRelationRequest;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.dto.codemap.CodeGenerateRequest;
import com.myide.backend.repository.codemap.CodeMapCacheRepository;
import com.myide.backend.repository.codemap.CodeSummaryRepository;
import com.myide.backend.service.analyzer.CodeAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeMapService {

    private final CodeSummaryRepository codeSummaryRepository;
    private final WorkspaceService workspaceService;
    private final CodeMapCacheRepository codeMapCacheRepository;
    private final ObjectMapper objectMapper;
    private final List<CodeAnalyzer> analyzers;

    @Transactional
    public CodeMapResponse getAnalyzedCodeMap(String workspaceId, String projectName, String branchName) {
        return getAnalyzedCodeMap(workspaceId, projectName, branchName, "JAVA");
    }

    @Transactional
    public CodeMapResponse getAnalyzedCodeMap(String workspaceId, String projectName, String branchName, String language) {
        String safeBranch = branchName == null ? "master" : branchName;
        String safeLanguage = language == null ? "JAVA" : language;

        Optional<CodeMapCache> cachedData = codeMapCacheRepository.findByWorkspaceIdAndProjectNameAndBranchName(workspaceId, projectName, safeBranch);

        if (cachedData.isPresent()) {
            try {
                log.info("⚡ [CodeMap] 캐시 히트! DB 데이터를 DTO로 역직렬화하여 반환합니다.");
                return objectMapper.readValue(cachedData.get().getMapDataJson(), CodeMapResponse.class);
            } catch (Exception e) {
                log.warn("🚨 캐시 역직렬화 실패! 망가진 데이터를 삭제하고 재분석을 진행합니다.", e);
                codeMapCacheRepository.delete(cachedData.get());
                codeMapCacheRepository.flush();
            }
        }

        log.info("🐢 [CodeMap] 캐시 미스! {} 언어 파일 파싱을 시작합니다.", safeLanguage);
        CodeMapResponse analyzedData = doAnalyzeWorkspace(workspaceId, projectName, safeBranch, safeLanguage);

        try {
            String json = objectMapper.writeValueAsString(analyzedData);
            Optional<CodeMapCache> doubleCheck = codeMapCacheRepository.findByWorkspaceIdAndProjectNameAndBranchName(workspaceId, projectName, safeBranch);

            if (doubleCheck.isPresent()) {
                CodeMapCache existing = doubleCheck.get();
                existing.setMapDataJson(json);
                codeMapCacheRepository.save(existing);
            } else {
                codeMapCacheRepository.save(CodeMapCache.builder()
                        .workspaceId(workspaceId)
                        .projectName(projectName)
                        .branchName(safeBranch)
                        .mapDataJson(json)
                        .build());
            }
        } catch (Exception e) {
            log.error("코드맵 캐시 저장 실패", e);
        }

        return analyzedData;
    }

    @Transactional
    public void invalidateCache(String workspaceId, String projectName, String branchName) {
        String safeBranch = branchName == null ? "master" : branchName;
        log.info("🗑️ [CodeMap] 파일 변경 감지! DB 캐시 무효화 (Project: {})", projectName);
        codeMapCacheRepository.deleteByWorkspaceIdAndProjectNameAndBranchName(workspaceId, projectName, safeBranch);
    }

    private CodeMapResponse doAnalyzeWorkspace(String workspaceId, String projectName, String branchName, String language) {
        Path projectPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);

        CodeAnalyzer selectedAnalyzer = analyzers.stream()
                .filter(analyzer -> analyzer.supports(language))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 언어 템플릿입니다: " + language));

        return selectedAnalyzer.analyze(projectPath.toString());
    }

    @Transactional
    public void createComponent(CreateComponentRequest req) {
        Path branchPath = workspaceService.getProjectPath(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
        String rawName = req.getName();
        String pureClassName = rawName;
        String packagePath = "";

        if (rawName.contains("/")) {
            int lastSlashIndex = rawName.lastIndexOf('/');
            pureClassName = rawName.substring(lastSlashIndex + 1);
            String dirPath = rawName.substring(0, lastSlashIndex);
            if (dirPath.contains("src/main/java/")) {
                packagePath = dirPath.replace("src/main/java/", "").replace("/", ".");
            }
        }

        String ext = ".java";
        String boilerplateCode = "";

        if ("REACT_COMPONENT".equals(req.getType())) {
            ext = ".jsx";
            boilerplateCode = "import React from 'react';\n\nexport default function " + pureClassName + "() {\n    return (\n        <div className=\"p-4\">\n            <h1>" + pureClassName + " Component</h1>\n        </div>\n    );\n}\n";
        } else if ("PYTHON_CLASS".equals(req.getType())) {
            ext = ".py";
            boilerplateCode = "class " + pureClassName + ":\n    def __init__(self):\n        pass\n";
        } else {
            boilerplateCode = generateJavaBoilerplate(pureClassName, req.getType(), packagePath);
        }

        Path filePath = branchPath.resolve(rawName + ext);

        if (Files.exists(filePath)) {
            throw new RuntimeException("이미 존재하는 컴포넌트입니다: " + rawName + ext);
        }

        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, boilerplateCode, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            invalidateCache(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
        } catch (IOException e) {
            throw new RuntimeException("파일 생성 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    private String generateJavaBoilerplate(String className, String type, String packageName) {
        StringBuilder code = new StringBuilder();
        if (packageName != null && !packageName.trim().isEmpty()) {
            code.append("package ").append(packageName).append(";\n\n");
        }
        switch (type.toUpperCase()) {
            case "INTERFACE":
                code.append("public interface ").append(className).append(" {\n}\n");
                break;
            case "ABSTRACT":
                code.append("public abstract class ").append(className).append(" {\n}\n");
                break;
            case "EXCEPTION":
                code.append("public class ").append(className).append(" extends RuntimeException {\n")
                        .append("    public ").append(className).append("(String message) {\n")
                        .append("        super(message);\n    }\n}\n");
                break;
            case "CLASS":
            default:
                code.append("public class ").append(className).append(" {\n}\n");
                break;
        }
        return code.toString();
    }

    private void checkValidJavaFile(String fileName) {
        if (fileName.endsWith(".js") || fileName.endsWith(".jsx") || fileName.endsWith(".py") || fileName.endsWith(".ts")) {
            throw new RuntimeException("🚨 자동 코드 주입 기능은 현재 Java 환경에서만 완벽 지원됩니다.");
        }
    }

    @Transactional
    public void createRelation(CreateRelationRequest req) {
        String sourceFileName = req.getSourceNode();
        if (!sourceFileName.contains(".")) sourceFileName += ".java";
        checkValidJavaFile(sourceFileName);

        String targetPath = req.getTargetNode();
        Path projectPath = workspaceService.getProjectPath(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
        Path sourcePath = projectPath.resolve(sourceFileName);
        Path targetFileFullPath = projectPath.resolve(targetPath.endsWith(".java") ? targetPath : targetPath + ".java");

        if (!Files.exists(sourcePath)) throw new RuntimeException("Source 파일을 찾을 수 없습니다.");

        try {
            CompilationUnit cuSource = StaticJavaParser.parse(sourcePath);

            // 💡 단방향 주입 로직 실행
            injectUnidirectionalRelation(cuSource, targetFileFullPath, req.getRelationType());

            Files.writeString(sourcePath, cuSource.toString(), StandardCharsets.UTF_8);
            invalidateCache(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());

        } catch (Exception e) {
            throw new RuntimeException("관계 생성 중 오류: " + e.getMessage());
        }
    }

    // 💡 [핵심 수정] DI, Extends, Implements AST 조작 로직 방어적 개선
    private void injectUnidirectionalRelation(CompilationUnit cu, Path targetFileFullPath, String relationType) {
        String pureTargetClassName = targetFileFullPath.getFileName().toString().replace(".java", "");
        String targetFqcn = pureTargetClassName;

        if (Files.exists(targetFileFullPath)) {
            try {
                CompilationUnit targetCu = StaticJavaParser.parse(targetFileFullPath);
                String packageName = targetCu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");
                if (!packageName.isEmpty()) {
                    targetFqcn = packageName + "." + pureTargetClassName;
                }
            } catch (IOException e) {
                throw new RuntimeException("대상 파일 분석 실패", e);
            }
        }

        final String finalFqcn = targetFqcn;
        boolean importExists = cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().equals(finalFqcn));

        if (!importExists) {
            cu.addImport(finalFqcn);
        }

        TypeDeclaration<?> typeDecl = cu.getPrimaryType().orElse(cu.getTypes().isEmpty() ? null : cu.getType(0));
        if (typeDecl != null && typeDecl.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();
            String fieldName = Character.toLowerCase(pureTargetClassName.charAt(0)) + pureTargetClassName.substring(1);

            switch (relationType.toUpperCase()) {
                case "EXTENDS":
                    // 다중 상속 금지 방어
                    if (classDecl.getExtendedTypes().isEmpty()) {
                        classDecl.addExtendedType(pureTargetClassName);
                    } else {
                        throw new IllegalStateException("이미 다른 클래스를 상속받고 있습니다.");
                    }
                    break;

                case "IMPLEMENTS":
                    boolean alreadyImplemented = classDecl.getImplementedTypes().stream()
                            .anyMatch(type -> type.getNameAsString().equals(pureTargetClassName));
                    if (!alreadyImplemented) {
                        classDecl.addImplementedType(pureTargetClassName);
                    }
                    break;

                case "INJECTS":
                    // 1. Lombok @RequiredArgsConstructor 주입
                    if (cu.getImports().stream().noneMatch(i -> i.getNameAsString().equals("lombok.RequiredArgsConstructor"))) {
                        cu.addImport("lombok.RequiredArgsConstructor");
                    }
                    if (!classDecl.getAnnotationByName("RequiredArgsConstructor").isPresent()) {
                        classDecl.addAnnotation("RequiredArgsConstructor");
                    }
                    // 2. private final 의존성 주입 필드 생성
                    if (classDecl.getFieldByName(fieldName).isEmpty()) {
                        classDecl.addField(pureTargetClassName, fieldName, Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);
                    }
                    break;

                case "COMPOSITION":
                    // 일반 객체 참조
                    if (classDecl.getFieldByName(fieldName).isEmpty()) {
                        classDecl.addField(pureTargetClassName, fieldName, Modifier.Keyword.PRIVATE);
                    }
                    break;
            }
        }
    }

    @Transactional
    public void deleteRelation(CreateRelationRequest req) {
        String sourceFileName = req.getSourceNode();
        if (!sourceFileName.contains(".")) sourceFileName += ".java";

        checkValidJavaFile(sourceFileName);

        String rawTarget = req.getTargetNode().replace(".java", "");
        if (rawTarget.contains("/")) {
            rawTarget = rawTarget.substring(rawTarget.lastIndexOf('/') + 1);
        }
        final String pureTargetName = rawTarget.trim();

        Path sourcePath = workspaceService.getProjectPath(req.getWorkspaceId(), req.getProjectName(), req.getBranchName()).resolve(sourceFileName);
        if (!Files.exists(sourcePath)) throw new RuntimeException("파일을 찾을 수 없습니다.");

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourcePath);
            boolean isModified = false;

            log.info("🔍 [수사 시작] Main클래스의 상속/필드에서 {}를 찾아 삭제 시도", pureTargetName);

            List<ImportDeclaration> targetImports = cu.getImports().stream()
                    .filter(i -> i.getNameAsString().contains(pureTargetName))
                    .collect(Collectors.toList());
            if (!targetImports.isEmpty()) {
                targetImports.forEach(ImportDeclaration::remove);
                isModified = true;
                log.info("✂️ [CodeMap] Import 문 제거 완료");
            }

            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!type.isClassOrInterfaceDeclaration()) continue;
                ClassOrInterfaceDeclaration classDecl = type.asClassOrInterfaceDeclaration();

                boolean removedExtends = classDecl.getExtendedTypes().removeIf(t -> t.getNameAsString().contains(pureTargetName));
                if (removedExtends) {
                    isModified = true;
                    log.info("✂️ [CodeMap] 상속(extends {}) 문구 제거 성공", pureTargetName);
                }

                boolean removedImplements = classDecl.getImplementedTypes().removeIf(t -> t.getNameAsString().contains(pureTargetName));
                if (removedImplements) {
                    isModified = true;
                    log.info("✂️ [CodeMap] 구현(implements {}) 문구 제거 성공", pureTargetName);
                }

                List<FieldDeclaration> fieldsToRemove = classDecl.getFields().stream()
                        .filter(f -> f.toString().contains(pureTargetName))
                        .collect(Collectors.toList());
                if (!fieldsToRemove.isEmpty()) {
                    fieldsToRemove.forEach(FieldDeclaration::remove);
                    isModified = true;
                    log.info("✂️ [CodeMap] 멤버 변수({}) 제거 성공", pureTargetName);
                }
            }

            if (isModified) {
                Files.writeString(sourcePath, cu.toString(), StandardCharsets.UTF_8);
                log.info("✅ [CodeMap] 코드 수정 완료 및 파일 저장됨!");
                invalidateCache(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
            } else {
                log.warn("⚠️ [CodeMap] '{}'와 매칭되는 코드를 끝내 찾지 못했습니다.", pureTargetName);
            }

        } catch (Exception e) {
            throw new RuntimeException("관계 삭제 조작 중 오류: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void generateCodeComponent(CodeGenerateRequest req) {
        try {
            Path projectPath = workspaceService.getProjectPath(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
            String fileName = req.getFilePath();
            if (!fileName.contains(".")) fileName += ".java";

            checkValidJavaFile(fileName);

            Path targetFile = projectPath.resolve(fileName);

            if (!Files.exists(targetFile)) {
                throw new IllegalArgumentException("대상 파일을 찾을 수 없습니다: " + fileName);
            }

            CompilationUnit cu = StaticJavaParser.parse(targetFile);

            String pureClassName = req.getClassName();
            if (pureClassName.contains("/")) {
                pureClassName = pureClassName.substring(pureClassName.lastIndexOf('/') + 1);
            }
            pureClassName = pureClassName.replace(".java", "");

            ClassOrInterfaceDeclaration targetClass = cu.getClassByName(pureClassName)
                    .orElseThrow(() -> new IllegalArgumentException("파일 내부에 클래스가 존재하지 않습니다: " + req.getClassName() + " -> " + req.getFilePath()));

            Modifier.Keyword modifier = Modifier.Keyword.valueOf(req.getAccessModifier().toUpperCase());

            if ("VARIABLE".equalsIgnoreCase(req.getTargetType())) {
                if (targetClass.getFieldByName(req.getName()).isPresent()) {
                    throw new RuntimeException("이미 존재하는 변수명입니다: " + req.getName());
                }

                FieldDeclaration field = targetClass.addField(req.getDataType(), req.getName(), modifier);

                // 💡 [핵심 수정] 초기값 문자열 및 문자 리터럴 보정
                if (req.getInitialValue() != null && !req.getInitialValue().trim().isEmpty()) {
                    String val = req.getInitialValue().trim();
                    String dataTypeLower = req.getDataType().trim().toLowerCase();

                    // String 일 때 쌍따옴표가 없으면 강제 추가
                    if (dataTypeLower.equals("string") && !val.startsWith("\"") && !val.endsWith("\"")) {
                        val = "\"" + val + "\"";
                    }
                    // Char 일 때 홑따옴표가 없으면 강제 추가
                    else if ((dataTypeLower.equals("char") || dataTypeLower.equals("character")) && !val.startsWith("'") && !val.endsWith("'")) {
                        val = "'" + val + "'";
                    }
                    field.getVariable(0).setInitializer(val);
                }
                log.info("✅ [CodeMap] 변수 추가 완료: {} {} {}", req.getAccessModifier(), req.getDataType(), req.getName());

            } else if ("METHOD".equalsIgnoreCase(req.getTargetType())) {
                if (!targetClass.getMethodsByName(req.getName()).isEmpty()) {
                    throw new RuntimeException("동일한 이름의 메서드가 이미 존재합니다.");
                }

                com.github.javaparser.ast.body.MethodDeclaration method = targetClass.addMethod(req.getName(), modifier)
                        .setType(req.getDataType());

                if (req.getParameters() != null && !req.getParameters().trim().isEmpty()) {
                    String[] params = req.getParameters().split(",");
                    for (String param : params) {
                        if (!param.trim().isEmpty()) {
                            method.addParameter(StaticJavaParser.parseParameter(param.trim()));
                        }
                    }
                }

                String bodyCode = "{}";
                if (req.getBody() != null && !req.getBody().trim().isEmpty()) {
                    bodyCode = "{\n" + req.getBody() + "\n}";
                }
                method.setBody(StaticJavaParser.parseBlock(bodyCode));

                log.info("✅ [CodeMap] 메서드 추가 완료: {} {} {}()", req.getAccessModifier(), req.getDataType(), req.getName());

            } else {
                throw new IllegalArgumentException("지원하지 않는 타겟 타입입니다.");
            }

            Files.writeString(targetFile, cu.toString(), StandardCharsets.UTF_8);
            invalidateCache(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());

        } catch (Exception e) {
            log.error("코드 생성 중 오류 발생: ", e);
            throw new RuntimeException("코드 주입 실패: " + e.getMessage(), e);
        }
    }

    @Transactional
    public String getOrGenerateSummary(String workspaceId, String projectName, String branchName, String filePath) {
        try {
            Path projectPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
            Path targetFile = projectPath.resolve(filePath);

            if (!Files.exists(targetFile)) return "파일을 찾을 수 없습니다.";

            String content = Files.readString(targetFile, StandardCharsets.UTF_8);
            String currentHash = calculateHash(content);

            Optional<CodeSummary> existingSummary = codeSummaryRepository.findByWorkspaceIdAndProjectNameAndBranchNameAndFilePath(workspaceId, projectName, branchName, filePath);

            if (existingSummary.isPresent() && existingSummary.get().getFileHash().equals(currentHash)) {
                log.info("🚀 [Cache Hit] {} - 코드가 변경되지 않아 DB에 저장된 요약을 반환합니다.", filePath);
                return existingSummary.get().getSummaryText();
            }

            log.info("🤖 [Cache Miss] {} - 코드가 변경되었거나 최초 분석입니다. AI API를 호출합니다.", filePath);
            String newSummary = callAiApi(content);

            if (existingSummary.isPresent()) {
                existingSummary.get().updateSummary(currentHash, newSummary);
            } else {
                codeSummaryRepository.save(CodeSummary.builder()
                        .workspaceId(workspaceId)
                        .projectName(projectName)
                        .branchName(branchName)
                        .filePath(filePath)
                        .fileHash(currentHash)
                        .summaryText(newSummary)
                        .build());
            }
            return newSummary;

        } catch (Exception e) {
            log.error("요약 생성 중 오류 발생: ", e);
            return "분석 실패: " + e.getMessage();
        }
    }

    private String calculateHash(String content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String callAiApi(String code) {
        try { Thread.sleep(1500); } catch (InterruptedException e) {}
        return "✨ AI 컴포넌트 분석 완료 ✨\n" +
                "이 클래스는 프로젝트의 핵심 비즈니스 로직을 담당합니다.\n" +
                "코드 구조상 효율적인 자원 관리와 가독성을 고려하여 설계되었으며,\n" +
                "데이터 검증 및 외부 모듈 연동의 역할을 수행하고 있습니다.";
    }
}