package com.myide.backend.repository;

import com.myide.backend.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    // 특정 워크스페이스의 모든 프로젝트 조회
    List<Project> findAllByWorkspaceUuid(String workspaceUuid);

    // 워크스페이스 내에서 이름으로 프로젝트 찾기 (중복 체크용)
    Optional<Project> findByWorkspaceUuidAndName(String workspaceUuid, String name);
}