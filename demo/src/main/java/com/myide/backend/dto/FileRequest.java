package com.myide.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRequest {

    // [필수: 위치 정보]
    private String workspaceId;
    private String projectName;
    private String branchName; // 없으면 "main-repo"

    // [파일 정보]
    private String filePath;   // 대상 파일 경로
    private String code;       // 저장할 내용 (저장 시 필수)
    private String type;       // "file" or "folder" (생성 시 필수)

    // [이름 변경용]
    private String newName;
}