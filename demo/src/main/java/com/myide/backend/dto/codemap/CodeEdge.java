package com.myide.backend.dto.codemap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder // 💡 객체 생성을 유연하게 하기 위해 추가
@AllArgsConstructor
@NoArgsConstructor
public class CodeEdge {
    private String id;           // 엣지 고유 ID
    private String source;       // 시작 노드 ID
    private String target;       // 대상 노드 ID
    private String type;         // 선 스타일 (smoothstep 등)
    private String relationType; // 💡 기존 필드명 유지 (IMPORT, IMPLEMENTS, EXTENDS, COMPOSITION)
}