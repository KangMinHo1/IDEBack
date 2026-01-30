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
public class ProjectRequest {

    @NotBlank(message = "사용자 ID는 필수입니다.")
    private String userId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    @Pattern(regexp = "^[a-zA-Z0-9-_]+$", message = "프로젝트 이름은 영문, 숫자, -, _ 만 사용 가능합니다.")
    private String projectName;

    @NotNull(message = "언어 선택은 필수입니다.")
    private LanguageType language;

    /**
     * [추가됨] 파일 경로 또는 파일명
     * - 프로젝트 생성 시에는 null일 수 있음
     * - 파일 생성/저장 시에는 필수 (예: "src/utils/Helper.java")
     */
    private String filePath;

    /**
     * 소스 코드
     * - 파일 저장 시 필수
     * - 파일 생성 시에는 선택 (없으면 빈 파일 생성)
     */
    private String code;
}