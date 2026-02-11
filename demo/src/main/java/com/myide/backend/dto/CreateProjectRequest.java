package com.myide.backend.dto;

import com.myide.backend.domain.LanguageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateProjectRequest {

    @NotBlank(message = "워크스페이스 ID는 필수입니다.")
    private String workspaceId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    @Pattern(regexp = "^[a-zA-Z0-9-_]+$", message = "영문, 숫자, -, _ 만 사용 가능")
    private String projectName;

    @NotNull(message = "언어 선택은 필수입니다.")
    private LanguageType language;

    private String description;
    private String gitUrl;      // 깃허브 주소
}