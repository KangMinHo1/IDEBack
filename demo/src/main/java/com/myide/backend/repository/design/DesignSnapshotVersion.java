package com.myide.backend.repository.design;

import java.time.LocalDateTime;

/**
 * 설계 저장본의 버전 정보만 담는다. 최종 보고서 입력 캐시의 키로 쓴다.
 *
 * revision 만으로는 부족하다. 저장본이 지워졌다가 다시 만들어지면 revision 이
 * 1 부터 다시 세어져 옛 캐시와 겹칠 수 있으므로 id 와 수정 시각을 함께 본다.
 */
public record DesignSnapshotVersion(Long id, long revision, LocalDateTime updatedAt) {

    public String asKey() {
        return id + ":" + revision + ":" + updatedAt;
    }
}
