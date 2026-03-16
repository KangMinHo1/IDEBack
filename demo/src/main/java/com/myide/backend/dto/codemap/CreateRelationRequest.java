package com.myide.backend.dto.codemap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateRelationRequest {

    @NotBlank(message = "워크스페이스 ID는 필수입니다.")
    private String workspaceId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    @Pattern(regexp = "^[a-zA-Z0-9-_]+$", message = "영문, 숫자, -, _ 만 사용 가능합니다.")
    private String projectName;

    private String branchName;

    @NotBlank(message = "출발 노드(소스)는 필수입니다.")
    private String sourceNode;

    @NotBlank(message = "도착 노드(타겟)는 필수입니다.")
    private String targetNode;

    @NotBlank(message = "관계 타입은 필수입니다.")
    @Pattern(regexp = "^(?i)(EXTENDS|IMPLEMENTS|COMPOSITION|IMPORT)$", message = "올바른 관계 타입을 지정해주세요.")
    private String relationType;
}