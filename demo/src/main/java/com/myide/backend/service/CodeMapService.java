package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.myide.backend.domain.CodeMapCache;
import com.myide.backend.domain.CodeSummary;
import com.myide.backend.dto.codemap.CreateComponentRequest;
import com.myide.backend.dto.codemap.CreateRelationRequest;
// 💡 [핵심] 회원님의 응답 DTO 임포트
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.repository.CodeMapCacheRepository;
import com.myide.backend.repository.CodeSummaryRepository;
import com.myide.backend.service.analyzer.JavaAnalyzer;
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
    private final JavaAnalyzer javaAnalyzer;

    // =========================================================================
    // 💡 [아키텍처 핵심] 코드맵 분석 및 DB 캐싱 (DTO 역직렬화 정석 버전)
    // =========================================================================
    @Transactional
    public CodeMapResponse getAnalyzedCodeMap(String workspaceId, String projectName, String branchName) {
        String safeBranch = branchName == null ? "master" : branchName;

        // 1. 첫 번째 캐시 확인
        Optional<CodeMapCache> cachedData = codeMapCacheRepository.findByWorkspaceIdAndProjectNameAndBranchName(workspaceId, projectName, safeBranch);

        if (cachedData.isPresent()) {
            try {
                log.info("⚡ [CodeMap] 캐시 히트! DB 데이터를 DTO로 역직렬화하여 반환합니다.");

                // ✅ [정석] JSON 문자열을 CodeMapResponse 객체로 즉시 변환
                // (DTO에 @NoArgsConstructor가 추가되었으므로 이제 에러 없이 동작합니다.)
                return objectMapper.readValue(cachedData.get().getMapDataJson(), CodeMapResponse.class);

            } catch (Exception e) {
                log.warn("🚨 캐시 역직렬화 실패! 망가진 데이터를 삭제하고 재분석을 진행합니다.", e);
                codeMapCacheRepository.delete(cachedData.get());
                codeMapCacheRepository.flush();
                // 아래 '캐시 미스' 로직으로 자연스럽게 넘어갑니다.
            }
        }

        log.info("🐢 [CodeMap] 캐시 미스! 전체 파일 파싱을 시작합니다.");

        // 2. 무거운 파싱 작업 수행
        CodeMapResponse analyzedData = doAnalyzeWorkspace(workspaceId, projectName, safeBranch);

        // 3. 파싱 결과를 JSON으로 직렬화하여 DB에 저장
        try {
            String json = objectMapper.writeValueAsString(analyzedData);

            // Double-Check: 저장 직전 동시성 확인
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
        log.info("🗑️ [CodeMap] 파일 변경 감지! DB 캐시를 무효화(삭제)합니다. (Project: {})", projectName);
        codeMapCacheRepository.deleteByWorkspaceIdAndProjectNameAndBranchName(workspaceId, projectName, safeBranch);
    }

    // 반환 타입을 CodeMapResponse로 맞춤
    private CodeMapResponse doAnalyzeWorkspace(String workspaceId, String projectName, String branchName) {
        Path projectPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
        return javaAnalyzer.analyze(projectPath.toString());
    }

    // =========================================================================
    // 💡 시각적 스캐폴딩 (파일 및 관계 조작 로직)
    // =========================================================================

    @Transactional
    public void createComponent(CreateComponentRequest req) {
        Path branchPath = workspaceService.getProjectPath(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
        Path filePath = branchPath.resolve(req.getName() + ".java");

        if (Files.exists(filePath)) {
            throw new RuntimeException("이미 존재하는 컴포넌트입니다: " + req.getName() + ".java");
        }

        String boilerplateCode = generateJavaBoilerplate(req.getName(), req.getType());

        try {
            Files.writeString(filePath, boilerplateCode, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            invalidateCache(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
        } catch (IOException e) {
            throw new RuntimeException("파일 생성 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    private String generateJavaBoilerplate(String className, String type) {
        StringBuilder code = new StringBuilder();
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

    @Transactional
    public void createRelation(CreateRelationRequest req) {
        String sourceFileName = req.getSourceNode().endsWith(".java") ? req.getSourceNode() : req.getSourceNode() + ".java";
        String targetPath = req.getTargetNode(); // 예: "com/myide/PaymentProcessor.java"

        Path projectPath = workspaceService.getProjectPath(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
        Path sourcePath = projectPath.resolve(sourceFileName);
        Path targetFileFullPath = projectPath.resolve(targetPath.endsWith(".java") ? targetPath : targetPath + ".java");

        if (!Files.exists(sourcePath)) throw new RuntimeException("Source 파일을 찾을 수 없습니다.");

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourcePath);
            String pureTargetClassName = targetFileFullPath.getFileName().toString().replace(".java", "");

            // ✨ [패키지 추적 로직] 대상 파일의 패키지 선언문을 읽어옵니다.
            String targetFqcn = pureTargetClassName;
            if (Files.exists(targetFileFullPath)) {
                CompilationUnit targetCu = StaticJavaParser.parse(targetFileFullPath);
                String packageName = targetCu.getPackageDeclaration()
                        .map(pd -> pd.getNameAsString())
                        .orElse("");
                if (!packageName.isEmpty()) {
                    targetFqcn = packageName + "." + pureTargetClassName;
                }
            }

            // ✨ Import 자동 삽입 (이미 있으면 중복 삽입 안 함)
            final String finalFqcn = targetFqcn;
            boolean importExists = cu.getImports().stream()
                    .anyMatch(i -> i.getNameAsString().equals(finalFqcn));

            if (!importExists) {
                cu.addImport(finalFqcn);
                log.info("➕ [CodeMap] 정확한 패키지 경로로 Import 추가: {}", finalFqcn);
            }

            // 클래스 본문 수정 로직
            TypeDeclaration<?> typeDecl = cu.getPrimaryType().orElse(cu.getTypes().isEmpty() ? null : cu.getType(0));
            if (typeDecl != null && typeDecl.isClassOrInterfaceDeclaration()) {
                ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();

                switch (req.getRelationType().toUpperCase()) {
                    case "EXTENDS":
                        classDecl.addExtendedType(pureTargetClassName);
                        break;
                    case "IMPLEMENTS":
                        classDecl.addImplementedType(pureTargetClassName);
                        break;
                    case "COMPOSITION":
                        String fieldName = Character.toLowerCase(pureTargetClassName.charAt(0)) + pureTargetClassName.substring(1);
                        if (classDecl.getFieldByName(fieldName).isEmpty()) {
                            classDecl.addField(pureTargetClassName, fieldName, Modifier.Keyword.PRIVATE);
                        }
                        break;
                }
            }

            Files.writeString(sourcePath, cu.toString(), StandardCharsets.UTF_8);
            invalidateCache(req.getWorkspaceId(), req.getProjectName(), req.getBranchName());

        } catch (Exception e) {
            throw new RuntimeException("관계 생성 중 오류: " + e.getMessage());
        }
    }

    @Transactional
    public void deleteRelation(CreateRelationRequest req) {
        String sourceFileName = req.getSourceNode().endsWith(".java") ? req.getSourceNode() : req.getSourceNode() + ".java";

        // 1. 이름 정제
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

            // ✨ [핵심 1] Import 문 삭제 시도
            List<ImportDeclaration> targetImports = cu.getImports().stream()
                    .filter(i -> i.getNameAsString().contains(pureTargetName))
                    .collect(Collectors.toList());
            if (!targetImports.isEmpty()) {
                targetImports.forEach(ImportDeclaration::remove);
                isModified = true;
                log.info("✂️ [CodeMap] Import 문 제거 완료");
            }

            // ✨ [핵심 2] 클래스 내부 구성 요소 삭제
            for (TypeDeclaration<?> type : cu.getTypes()) {
                if (!type.isClassOrInterfaceDeclaration()) continue;
                ClassOrInterfaceDeclaration classDecl = type.asClassOrInterfaceDeclaration();

                // A. 상속(Extends) 제거
                // 💡 [수정] removeIf 결과가 하나라도 true면 isModified를 true로!
                boolean removedExtends = classDecl.getExtendedTypes().removeIf(t -> t.getNameAsString().contains(pureTargetName));
                if (removedExtends) {
                    isModified = true;
                    log.info("✂️ [CodeMap] 상속(extends {}) 문구 제거 성공", pureTargetName);
                }

                // B. 구현(Implements) 제거
                boolean removedImplements = classDecl.getImplementedTypes().removeIf(t -> t.getNameAsString().contains(pureTargetName));
                if (removedImplements) {
                    isModified = true;
                    log.info("✂️ [CodeMap] 구현(implements {}) 문구 제거 성공", pureTargetName);
                }

                // C. 필드 변수 제거
                List<FieldDeclaration> fieldsToRemove = classDecl.getFields().stream()
                        .filter(f -> f.toString().contains(pureTargetName))
                        .collect(Collectors.toList());
                if (!fieldsToRemove.isEmpty()) {
                    fieldsToRemove.forEach(FieldDeclaration::remove);
                    isModified = true;
                    log.info("✂️ [CodeMap] 멤버 변수({}) 제거 성공", pureTargetName);
                }
            }

            // ✨ [최종 결과] 하나라도 지워졌다면 파일 저장 및 캐시 파괴!
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

    // =========================================================================
    // 💡 AI 요약 관련 로직
    // =========================================================================

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