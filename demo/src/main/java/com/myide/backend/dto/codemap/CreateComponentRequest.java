package com.myide.backend.dto.codemap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateComponentRequest {

    @NotBlank(message = "워크스페이스 ID는 필수입니다.")
    private String workspaceId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    @Pattern(regexp = "^[a-zA-Z0-9-_]+$", message = "영문, 숫자, -, _ 만 사용 가능합니다.")
    private String projectName;

    private String branchName;

    // 💡 [보안] 클래스 이름에 경로 조작(../)이나 특수문자 금지
    @NotBlank(message = "클래스 이름은 필수입니다.")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "클래스 이름은 영문, 숫자, _ 만 사용 가능합니다.")
    private String name;

    @NotBlank(message = "컴포넌트 타입은 필수입니다.")
    @Pattern(regexp = "^(?i)(CLASS|INTERFACE|ABSTRACT|EXCEPTION)$", message = "올바른 컴포넌트 타입을 입력해주세요.")
    private String type;
}