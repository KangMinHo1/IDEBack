package com.myide.backend.repository.workspace;

import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    // 💡  User 엔티티의 id를 참조하도록 메서드명과 파라미터 타입(Long) 변경
    List<WorkspaceMember> findByUser_Id(Long userId);

    // 💡 [ 동일하게 User 객체의 id(Long)를 참조하도록 변경
    Optional<WorkspaceMember> findByWorkspace_UuidAndUser_Id(String workspaceUuid, Long userId);

    // 워크스페이스와 유저 객체를 받아서 존재하는지 확인하는 메서드
    boolean existsByWorkspaceAndUser(Workspace workspace, User user);
}