package com.myide.backend.dto.ide;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRequest {

    @NotBlank(message = "워크스페이스 ID는 필수입니다.")
    private String workspaceId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    private String projectName;

    private String branchName; // 없으면 "main-repo"

    // [핵심] '..'을 사용해서 상위 폴더로 몰래 빠져나가는 해킹 방지
    @NotBlank(message = "경로는 필수입니다.")
    private String filePath;

    private String code;

    @Pattern(regexp = "^(?i)(file|folder)$", message = "타입은 'file' 또는 'folder'만 가능합니다.")
    private String type;

    @Pattern(regexp = "^[^\\\\/:*?\"<>|]+$", message = "파일명에 사용할 수 없는 특수문자가 있습니다.")
    private String newName;
}