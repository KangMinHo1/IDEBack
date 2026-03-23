// 경로: src/main/java/com/myide/backend/dto/rearrange/RearrangeRequest.java
package com.myide.backend.dto.rearrange;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RearrangeRequest {
    private String workspaceId;
    private String viewName;
    private String prompt;
    private String branchName;
}