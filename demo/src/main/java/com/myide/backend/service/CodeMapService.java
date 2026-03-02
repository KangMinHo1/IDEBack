package com.myide.backend.service;

import com.myide.backend.domain.CodeSummary;
import com.myide.backend.repository.CodeSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeMapService {

    private final CodeSummaryRepository codeSummaryRepository;
    private final WorkspaceService workspaceService;

    @Transactional
    public String getOrGenerateSummary(String workspaceId, String projectName, String branchName, String filePath) {
        try {
            // 1. 실제 파일 절대 경로 조립
            Path projectPath = workspaceService.getProjectPath(workspaceId, projectName, branchName);
            Path targetFile = projectPath.resolve(filePath);

            if (!Files.exists(targetFile)) {
                return "파일을 찾을 수 없습니다.";
            }

            // 2. 파일 텍스트 읽어오기
            String content = Files.readString(targetFile, StandardCharsets.UTF_8);

            // 3. 현재 파일 내용의 고유 해시값(Hash) 계산
            String currentHash = calculateHash(content);

            // 4. DB에서 이 파일의 기존 요약본 찾기
            Optional<CodeSummary> existingSummary = codeSummaryRepository
                    .findByWorkspaceIdAndProjectNameAndBranchNameAndFilePath(workspaceId, projectName, branchName, filePath);

            // ✅ 캐시 히트(Cache Hit): DB에 데이터가 있고, 코드(해시값)가 한 글자도 안 바뀌었을 때
            if (existingSummary.isPresent() && existingSummary.get().getFileHash().equals(currentHash)) {
                log.info("🚀 [Cache Hit] {} - 코드가 변경되지 않아 DB에 저장된 요약을 반환합니다.", filePath);
                return existingSummary.get().getSummaryText();
            }

            // ❌ 캐시 미스(Cache Miss): 처음 누르거나, 코드가 수정되어서 해시값이 달라졌을 때
            log.info("🤖 [Cache Miss] {} - 코드가 변경되었거나 최초 분석입니다. AI API를 호출합니다.", filePath);

            // 5. AI 호출 (새로운 요약 생성)
            String newSummary = callAiApi(content);

            // 6. DB 갱신 또는 신규 저장
            if (existingSummary.isPresent()) {
                // 기존 데이터가 있으면 해시와 텍스트만 업데이트 (더티 체킹)
                existingSummary.get().updateSummary(currentHash, newSummary);
            } else {
                // 아예 처음이면 새로 만들어서 DB에 INSERT
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

    // 파일 내용을 짧은 암호문(해시)으로 바꾸는 SHA-256 계산기
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

    // 🤖 AI API 호출 가상 메서드 (추후 ChatGPT/Gemini 연동 코드로 교체할 부분)
    private String callAiApi(String code) {
        // 통신 지연 시간을 흉내 내기 위해 1.5초 대기 (로딩 UI 확인용)
        try { Thread.sleep(1500); } catch (InterruptedException e) {}

        return "✨ AI 컴포넌트 분석 완료 ✨\n" +
                "이 클래스는 프로젝트의 핵심 비즈니스 로직을 담당합니다.\n" +
                "코드 구조상 효율적인 자원 관리와 가독성을 고려하여 설계되었으며,\n" +
                "데이터 검증 및 외부 모듈 연동의 역할을 수행하고 있습니다.";
    }
}