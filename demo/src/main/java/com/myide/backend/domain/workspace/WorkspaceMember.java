package com.myide.backend.domain.workspace;

import com.myide.backend.domain.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "workspace_members", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"workspace_id", "user_id"})
})
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceRole role;

    // 💡 초대 대기 상태 관리를 위한 필드 추가
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JoinStatus status;

    public enum WorkspaceRole { OWNER, MEMBER } // 방장, 멤버
    public enum JoinStatus { PENDING, ACCEPTED } // 대기, 수락

    // 방장 생성 시에는 무조건 수락(ACCEPTED) 상태로 생성
    public static WorkspaceMember createOwner(Workspace workspace, User user) {
        return WorkspaceMember.builder()
                .workspace(workspace)
                .user(user)
                .role(WorkspaceRole.OWNER)
                .status(JoinStatus.ACCEPTED) // 방장은 가입 완료 상태
                .build();
    }

    // 💡 초대 수락 시 상태 변경 로직 (더티 체킹용)
    public void acceptInvitation() {
        this.status = JoinStatus.ACCEPTED;
    }
}