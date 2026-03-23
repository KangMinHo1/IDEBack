// 경로: src/main/java/com/myide/backend/dto/rearrange/RearrangeResponse.java
package com.myide.backend.dto.rearrange;

import com.myide.backend.domain.rearrange.VirtualFileTree;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RearrangeResponse {
    private Long id;
    private String viewName;
    private String criteria;
    private String treeDataJson;
    private boolean isActive;
    private String branchName; // 💡 [NEW]

    public static RearrangeResponse from(VirtualFileTree tree) {
        return RearrangeResponse.builder()
                .id(tree.getId())
                .viewName(tree.getViewName())
                .criteria(tree.getCriteria())
                .treeDataJson(tree.getTreeDataJson())
                .isActive(tree.isActive())
                .branchName(tree.getBranchName()) // 💡 [NEW]
                .build();
    }
}