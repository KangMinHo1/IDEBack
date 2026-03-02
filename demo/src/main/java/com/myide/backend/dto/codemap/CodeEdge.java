package com.myide.backend.dto.codemap;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CodeEdge {
    private String id;       // 엣지 고유 ID (ex: e-source-target)
    private String source;   // Import 하는 쪽 파일 ID
    private String target;   // Import 당하는 쪽 파일 ID
    private String type;     // 프론트 ReactFlow 선 스타일 (step)
    private String relationType; // IMPORT, IMPLEMENTS, EXTENDS 구분용
}