package com.myide.backend.dto.aiassist;

import lombok.Data;

@Data
public class AiAssistRequest {
    private String workspaceId;
    private String projectName;
    private String branchName;
    private String filePath;    // 대상 파일 (예: Main.java)
    private String userQuery;   // 사용자의 요청 ("이 부분 예외처리 해줘")
    private String currentCode; // 에디터의 현재 전체 코드
}
