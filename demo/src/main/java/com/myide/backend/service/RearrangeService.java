// 경로: src/main/java/com/myide/backend/service/RearrangeService.java
package com.myide.backend.service;

import com.myide.backend.domain.rearrange.VirtualFileTree;
import com.myide.backend.dto.rearrange.RearrangeRequest;
import com.myide.backend.dto.rearrange.RearrangeResponse;
import com.myide.backend.repository.rearrange.VirtualFileTreeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RearrangeService {

    private final VirtualFileTreeRepository rearrangeRepository;
    private final CoreAiService coreAiService; // 💡 공통 통신 모듈 주입!

    // 워크스페이스 실제 경로 (본인 환경에 맞게 수정)
    private static final String BASE_DIR = "C:\\WebIDE\\workspaces";

    @Transactional
    public RearrangeResponse generateVirtualTree(RearrangeRequest request) {

        // 🚨 [추가된 핵심 로직] 중복 방어 로직 (Fail-Fast)
        // AI API를 호출하여 시간과 비용을 쓰기 전에, 이미 존재하는 뷰 이름인지 먼저 검사합니다.
        if (rearrangeRepository.existsByWorkspaceIdAndViewName(request.getWorkspaceId(), request.getViewName())) {
            throw new IllegalArgumentException("해당 워크스페이스에 이미 '" + request.getViewName() + "' 이(가) 존재합니다.");
        }

        Path workspacePath = Paths.get(BASE_DIR, request.getWorkspaceId());
        List<String> allFiles;
        try {
            allFiles = Files.walk(workspacePath)
                    .filter(Files::isRegularFile)
                    .map(path -> workspacePath.relativize(path).toString().replace("\\", "/"))
                    .filter(path -> !path.contains(".git"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("파일 목록을 읽어오는데 실패했습니다.", e);
        }

        String systemPrompt = String.format(
                "너는 수석 소프트웨어 아키텍트야. 다음 파일 목록을 확인하고, 사용자의 요청(%s)에 맞춰 가상의 폴더 트리 구조를 만들어줘.\n" +
                        "반드시 순수한 JSON 배열 형태로 응답해야 하며, 폴더는 'children'을 가지고, 최하위 파일 노드는 'realPath'를 가져야 해.\n" +
                        "예시 형식: [{ \"name\": \"새폴더명\", \"type\": \"VIRTUAL_FOLDER\", \"children\": [ { \"name\": \"Main.java\", \"type\": \"FILE\", \"realPath\": \"자바1/Main.java\" } ] }]\n" +
                        "파일 목록: %s",
                request.getPrompt(), String.join(", ", allFiles)
        );

        try {
            // 💡 CoreAiService 호출
            String rawAiResponse = coreAiService.generateText(systemPrompt);
            String generatedJsonTree = extractJsonArray(rawAiResponse);

            VirtualFileTree newTree = VirtualFileTree.builder()
                    .workspaceId(request.getWorkspaceId())
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
        String clean = rawResponse.replaceAll("```json", "").replaceAll("```", "").trim();
        int startIndex = clean.indexOf("[");
        int endIndex = clean.lastIndexOf("]");
        if (startIndex != -1 && endIndex != -1) {
            return clean.substring(startIndex, endIndex + 1);
        }
        return clean;
    }

    @Transactional
    public void activateTree(String workspaceId, Long treeId) {
        rearrangeRepository.deactivateAllByWorkspaceId(workspaceId);
        VirtualFileTree tree = rearrangeRepository.findById(treeId).orElseThrow();
        tree.setActive(true);
    }

    @Transactional
    public void deactivateAll(String workspaceId) {
        rearrangeRepository.deactivateAllByWorkspaceId(workspaceId);
    }

    @Transactional(readOnly = true)
    public RearrangeResponse getActiveTree(String workspaceId) {
        return rearrangeRepository.findByWorkspaceIdAndIsActiveTrue(workspaceId)
                .map(RearrangeResponse::from)
                .orElse(null);
    }

    // 💡 [NEW] 워크스페이스에 저장된 모든 가상 트리 목록 가져오기 (최신순)
    @Transactional(readOnly = true)
    public List<RearrangeResponse> getAllTrees(String workspaceId) {
        return rearrangeRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId)
                .stream()
                .map(RearrangeResponse::from)
                .collect(Collectors.toList());
    }
}