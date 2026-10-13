// 경로: src/main/java/com/myide/backend/service/saferun/AuthorStatus.java
package com.myide.backend.service.saferun;

/** 작성자가 고친 파일이 전부 문법 검사를 통과하면 READY, 하나라도 실패하면 EDITING. */
public enum AuthorStatus {
    READY,
    EDITING
}
