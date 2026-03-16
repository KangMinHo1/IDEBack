package com.myide.backend.dto.devlog;

import lombok.Builder;
import lombok.Getter;



@Getter
@Builder
public class WorkspaceListItemResponse {
    private String uuid;
    private String name;
    private String mode; // personal / team
    private String lastUpdatedDate;
    private int projectCount;
}