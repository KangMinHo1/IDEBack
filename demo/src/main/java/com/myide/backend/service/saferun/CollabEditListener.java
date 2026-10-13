// 경로: src/main/java/com/myide/backend/service/saferun/CollabEditListener.java
package com.myide.backend.service.saferun;

/**
 * 동시 편집 방에서 누군가 문서를 고쳤을 때 불린다.
 *
 * 협업 핸들러는 중계가 본업이라 안전 실행을 직접 알지 않게 하려고 사이에 둔다.
 */
public interface CollabEditListener {

    void onEdit(String workspaceId, String projectName, String branchName, String filePath, Long userId);
}
