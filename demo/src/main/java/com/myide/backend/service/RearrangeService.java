// 경로: src/main/java/com/myide/backend/service/RearrangeService.java
package com.myide.backend.service;

import com.myide.backend.domain.rearrange.VirtualFileTree;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.rearrange.RearrangeRequest;
import com.myide.backend.dto.rearrange.RearrangeResponse;
import com.myide.backend.repository.rearrange.VirtualFileTreeRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RearrangeService {

    private final VirtualFileTreeRepository rearrangeRepository;
    private final CoreAiService coreAiService;
    private final WorkspaceRepository workspaceRepository;

    @Transactional
    public RearrangeResponse generateVirtualTree(RearrangeRequest request) {

        String targetBranch = (request.getBranchName() != null && !request.getBranchName().trim().isEmpty())
                ? request.getBranchName() : "master";

        if (rearrangeRepository.existsByWorkspaceIdAndBranchNameAndViewName(request.getWorkspaceId(), targetBranch, request.getViewName())) {
            throw new IllegalArgumentException("해당 브랜치에 이미 '" + request.getViewName() + "' 뷰가 존재합니다.");
        }

        Workspace workspace = workspaceRepository.findById(request.getWorkspaceId())
                .orElseThrow(() -> new IllegalArgumentException("워크스페이스를 찾을 수 없습니다."));

        Path workspacePath = Paths.get(workspace.getPath());
        List<String> allFiles;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

        try {
            allFiles = Files.walk(workspacePath)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String p = path.toString().replace("\\", "/");
                        if (p.contains("/.git/") || p.contains("/node_modules/") || p.contains("/build/") || p.contains("/.idea/")) return false;
                        return p.contains("/" + targetBranch + "/");
                    })
                    .map(path -> {
                        try {
                            String relativePath = workspacePath.relativize(path).toString().replace("\\", "/");

                            // 💡 [완벽 보완] 브랜치명이 경로 어디에 있든 완벽하게 제거!
                            String branchToken = targetBranch + "/";
                            if (relativePath.startsWith(branchToken)) {
                                relativePath = relativePath.substring(branchToken.length());
                            } else if (relativePath.contains("/" + branchToken)) {
                                relativePath = relativePath.replace("/" + branchToken, "/");
                            }

                            // 💡 [핵심] AI가 헷갈리지 않게 진짜 파일명과 전체 경로를 분리해서 떠먹여줍니다.
                            String fileName = path.getFileName().toString();
                            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                            String dateStr = formatter.format(lastModifiedTime.toInstant());

                            return String.format("[경로: %s | 파일명: %s | 수정일: %s]", relativePath, fileName, dateStr);
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("파일 목록을 읽어오는데 실패했습니다.", e);
        }

        // 💡 [프롬프트 마스터피스] AI의 요약병, 환각, 대충 분류를 원천 차단하는 5대 절대 규칙
        String systemPrompt = String.format(
                "너는 수석 소프트웨어 아키텍트야. 사용자의 요청('%s')에 따라 아래 제공된 [원본 파일 목록]을 완벽하게 재배치하여 JSON 가상 폴더 트리를 생성해.\n" +
                        "반드시 순수한 JSON 배열([])만 응답하고 마크다운 기호(```json 등)는 절대 사용하지 마라.\n\n" +
                        "🚨 [가장 중요한 5대 엄수 규칙]\n" +
                        "1. 누락 절대 금지: [원본 파일 목록]에 있는 모든 파일은 100%% JSON 트리의 최하위 노드(type: \"FILE\")로 빠짐없이 들어가야 한다.\n" +
                        "2. 분류 기준 엄격 적용: 사용자가 '언어별' 또는 '확장자별' 분류를 원하면, 파일 확장자(.java, .html, .js 등)를 정확히 분석하여 알맞은 폴더(예: Java, HTML, JavaScript)에 완벽히 분리해라. 대충 섞어두지 마라.\n" + // 💡 [NEW] 분류 강제 규칙 추가!
                        "3. 파일명 및 경로 원본 유지: 파일 노드의 'name'과 'realPath'는 제공된 텍스트를 100%% 동일하게 복사해야 한다. 임의 수정 시 시스템이 파괴된다.\n" +
                        "4. 빈 폴더 금지: 파일이 없는 VIRTUAL_FOLDER는 생성하지 마라.\n" +
                        "5. 폴더와 파일의 명확한 구분: 폴더는 'VIRTUAL_FOLDER', 파일은 'FILE' 타입을 정확히 명시하라.\n\n" +
                        "[응답 JSON 예시]\n" +
                        "[\n  {\n    \"name\": \"HTML\",\n    \"type\": \"VIRTUAL_FOLDER\",\n    \"children\": [\n      {\n        \"name\": \"index.html\",\n        \"type\": \"FILE\",\n        \"realPath\": \"프론트엔드/index.html\"\n      }\n    ]\n  }\n]\n\n" +
                        "[원본 파일 목록]\n%s",
                request.getPrompt(), String.join("\n", allFiles)
        );

        try {
            String rawAiResponse = coreAiService.generateText(systemPrompt);
            String generatedJsonTree = extractJsonArray(rawAiResponse);

            VirtualFileTree newTree = VirtualFileTree.builder()
                    .workspaceId(request.getWorkspaceId())
                    .branchName(targetBranch)
                    .viewName(request.getViewName())
                    .criteria(request.getPrompt())
                    .treeDataJson(generatedJsonTree)
                    .isActive(false)
                    .build();

            return RearrangeResponse.from(rearrangeRepository.save(newTree));

        } catch (Exception e) {
            log.error("가상 트리 생성 중 에러 발생", e);
            throw new RuntimeException("AI를 이용한 가상 트리 생성에 실패했습니다.");
        }
    }

    private String extractJsonArray(String rawResponse) {
        // 정규식 개선: 대소문자 무시 및 줄바꿈 포함 안전하게 추출
        String clean = rawResponse.replaceAll("(?i)```json", "").replaceAll("```", "").trim();
        int startIndex = clean.indexOf("[");
        int endIndex = clean.lastIndexOf("]");
        if (startIndex != -1 && endIndex != -1) {
            return clean.substring(startIndex, endIndex + 1);
        }
        return clean;
    }

    @Transactional
    public void activateTree(String workspaceId, String branchName, Long treeId) {
        rearrangeRepository.deactivateAllByWorkspaceIdAndBranchName(workspaceId, branchName);
        VirtualFileTree tree = rearrangeRepository.findById(treeId).orElseThrow();
        tree.setActive(true);
    }

    @Transactional
    public void deactivateAll(String workspaceId, String branchName) {
        rearrangeRepository.deactivateAllByWorkspaceIdAndBranchName(workspaceId, branchName);
    }

    @Transactional(readOnly = true)
    public RearrangeResponse getActiveTree(String workspaceId, String branchName) {
        return rearrangeRepository.findByWorkspaceIdAndBranchNameAndIsActiveTrue(workspaceId, branchName)
                .map(RearrangeResponse::from)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<RearrangeResponse> getAllTrees(String workspaceId, String branchName) {
        return rearrangeRepository.findByWorkspaceIdAndBranchNameOrderByCreatedAtDesc(workspaceId, branchName)
                .stream()
                .map(RearrangeResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteTree(String workspaceId, Long treeId) {
        VirtualFileTree tree = rearrangeRepository.findById(treeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 뷰입니다."));

        if (!tree.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("해당 뷰를 삭제할 권한이 없습니다.");
        }

        rearrangeRepository.delete(tree);
    }

    // 💡 [NEW] 사용자가 드래그 앤 드롭으로 수정한 트리 데이터를 DB에 저장하는 기능!
    @Transactional
    public void updateVirtualTree(String workspaceId, Long treeId, String newTreeDataJson) {
        VirtualFileTree tree = rearrangeRepository.findById(treeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 뷰입니다."));

        // 방어 로직: 내 워크스페이스의 뷰가 맞는지 확인
        if (!tree.getWorkspaceId().equals(workspaceId)) {
            throw new IllegalArgumentException("해당 뷰를 수정할 권한이 없습니다.");
        }

        // 새로운 트리 데이터로 덮어쓰기!
        tree.setTreeDataJson(newTreeDataJson);
    }
}