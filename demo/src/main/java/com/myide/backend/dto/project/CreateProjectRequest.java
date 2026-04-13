package com.myide.backend.dto.project;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProjectRequest {

    @NotBlank(message = "워크스페이스 ID는 필수입니다.")
    private String workspaceId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    private String projectName;

    @NotNull(message = "언어 선택은 필수입니다.")
    private LanguageType language;

    //  어떤 템플릿(프레임워크)으로 생성할지 받습니다.
    @NotNull(message = "템플릿 타입은 필수입니다.")
    private TemplateType templateType;

    private String description;
    private String gitUrl;
}