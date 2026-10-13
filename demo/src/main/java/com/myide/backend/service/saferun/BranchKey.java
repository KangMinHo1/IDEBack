// 경로: src/main/java/com/myide/backend/service/saferun/BranchKey.java
package com.myide.backend.service.saferun;

/**
 * 안전 실행이 다루는 단위. 팀이 함께 고치는 "한 브랜치 폴더"를 가리킨다.
 *
 * 브랜치 이름은 폴더 이름이 아니라 진짜 브랜치 이름이다(feature/login 처럼 슬래시 포함).
 * 폴더 이름으로 바꾸는 일은 SafeRunPaths 가 맡는다.
 */
public record BranchKey(String workspaceId, String projectName, String branchName) {

    private static final String DEFAULT_BRANCH_NAME = "master";

    /**
     * 비어 있거나 옛 이름(main-repo)인 브랜치를 master 로 맞춘다.
     * WorkspaceService.normalizeBranchName 과 같은 규칙이다. 여기서 어긋나면 같은
     * 브랜치의 장부가 둘로 갈라져 작성자가 서로 안 보인다.
     */
    public static BranchKey of(String workspaceId, String projectName, String branchName) {
        String branch = branchName == null ? "" : branchName.trim();

        if (branch.isEmpty() || "main-repo".equals(branch)) {
            branch = DEFAULT_BRANCH_NAME;
        }

        return new BranchKey(workspaceId, projectName, branch);
    }
}
