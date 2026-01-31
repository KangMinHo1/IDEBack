package com.myide.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceRequest {
    private String workspaceId; // 워크스페이스 UUID
    private String name;        // 워크스페이스 이름 (생성 시)
    private String userId;      // 사용자 ID

    private String filePath;    // 파일 경로 (예: my-app/Main.java)
    private String code;        // 파일 내용 (저장 시)
    private String language;    // 언어 (프로젝트 생성 시)

    // [▼ 추가된 필드: 이 두 개가 있어야 에러가 안 납니다!]
    private String type;        // "file" 또는 "folder" (파일 생성 시 필수)
    private String newName;     // 변경할 이름 (이름 변경 시 필수)
}