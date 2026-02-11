package com.myide.backend.dto;

import com.myide.backend.domain.LanguageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuildRequest {
    private String workspaceId;
    private String projectName;
    private String branchName;
    private LanguageType language;
}