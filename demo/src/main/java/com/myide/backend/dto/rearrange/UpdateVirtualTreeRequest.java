// 경로: src/main/java/com/myide/backend/dto/rearrange/UpdateVirtualTreeRequest.java
package com.myide.backend.dto.rearrange;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateVirtualTreeRequest {
    private String treeDataJson; // 드래그 앤 드롭으로 수정된 새로운 JSON 트리
}