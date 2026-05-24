package com.myide.backend.dto.codemap;

import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter; // 💡 Setter 추가

@Getter
@Setter // 💡 프론트엔드의 JSON 데이터를 이 클래스에 안전하게 매핑(저장)하기 위해 추가
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeGenerateRequest {

    @NotBlank private String workspaceId;
    @NotBlank private String projectName;
    @NotBlank private String branchName;

    // 💡 [핵심 추가] 백엔드 에러의 원인이었던 '파일 경로' 필드 추가!
    @NotBlank private String filePath;

    @NotBlank private String className;
    @NotBlank private String targetType;    // "VARIABLE" 또는 "METHOD"

    @NotBlank private String accessModifier;
    @NotBlank private String dataType;
    @NotBlank private String name;

    // 💡 선택적(Optional) 입력 필드들
    private String initialValue; // 변수 초기값 (예: "\"기본값\"", "100")
    private String parameters;   // 파라미터 (예: "String name, int age")
    private String body;         // 메서드 내용 (예: "this.name = name; return true;")
}