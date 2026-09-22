package com.myide.backend.dto.codemap;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CodeNode {
    private String id;
    private String label;
    private String type;
    private String role;
    private String packageName; // 패키지(폴더) 그룹화를 위한 변수

    /**
     * 설계 관리의 코드 생성이 만든 파일인지. 코드맵이 AI 생성 표시를 붙인다.
     *
     * 분석기가 아니라 CodeMapService 가 분석을 마친 뒤 한꺼번에 채운다.
     * 판별 근거는 GeneratedMarker 를 볼 것.
     */
    private boolean aiGenerated;

    /**
     * 분석기들이 쓰는 생성자.
     *
     * 분석기 네 개에 걸쳐 열 군데서 이 모양으로 노드를 만든다. aiGenerated 는
     * 분석기가 알 수 없는 값이라 여기서는 false 로 두고, 나중에 한 곳에서 채운다.
     */
    public CodeNode(String id, String label, String type, String role, String packageName) {
        this(id, label, type, role, packageName, false);
    }
}
