// 경로: src/main/java/com/myide/backend/dto/rearrange/RearrangeRequest.java
package com.myide.backend.dto.rearrange;

import lombok.Getter;

@Getter
public class RearrangeRequest {
    private String workspaceId;
    private String viewName;
    private String prompt; // 사용자가 입력한 재배치 기준 (예: "확장자별로 폴더 묶어줘")
}