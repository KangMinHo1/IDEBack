package com.myide.backend.dto.codemap;

import lombok.Data;

@Data
public class CreateRelationRequest {
    private String workspaceId;
    private String projectName;
    private String branchName;
    private String sourceNode;   // 선이 출발한 노드 (코드가 추가될 파일)
    private String targetNode;   // 선이 도착한 노드 (상속/참조할 대상)
    private String relationType; // EXTENDS, IMPLEMENTS, COMPOSITION
}
