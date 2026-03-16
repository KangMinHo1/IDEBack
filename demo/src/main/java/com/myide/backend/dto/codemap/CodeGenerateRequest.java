package com.myide.backend.dto.codemap;

import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeGenerateRequest {

    @NotBlank private String workspaceId;
    @NotBlank private String projectName;
    @NotBlank private String branchName;

    @NotBlank private String className;
    @NotBlank private String targetType;    // "VARIABLE" 또는 "METHOD"

    @NotBlank private String accessModifier;
    @NotBlank private String dataType;
    @NotBlank private String name;

    // 💡 [NEW] 선택적(Optional) 입력 필드들
    private String initialValue; // 변수 초기값 (예: "\"기본값\"", "100")
    private String parameters;   // 파라미터 (예: "String name, int age")
    private String body;         // 메서드 내용 (예: "this.name = name; return true;")
}