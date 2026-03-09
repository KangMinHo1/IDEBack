package com.myide.backend.repository;

import com.myide.backend.domain.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    List<WorkspaceMember> findByUserId(String userId);

    Optional<WorkspaceMember> findByWorkspaceUuidAndUserId(String workspaceUuid, String userId);
}