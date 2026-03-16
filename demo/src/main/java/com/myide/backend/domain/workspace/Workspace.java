package com.myide.backend.domain.workspace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.User; // 💡 User 엔티티 임포트
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace {

    @Id
    private String uuid;
    private String name;
    private String description;

    @Column(nullable = false)
    private String path;

    // 💡 [핵심] String ownerId 대신 User 객체와 연관관계를 맺습니다!
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @OneToMany(mappedBy = "workspace", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Project> projects = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceType type;

    private LocalDateTime updatedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Workspace(String uuid, String name, User owner, String description, String path, WorkspaceType type) {
        this.uuid = uuid;
        this.name = name;
        this.owner = owner; // 💡 User 객체 주입
        this.description = description;
        this.path = path;
        this.type = type;
        this.updatedAt = LocalDateTime.now();
    }

    // ✅ 개인 워크스페이스 생성
    public static Workspace createPersonal(User owner, String name, String description, String path) {
        return Workspace.builder()
                .uuid(UUID.randomUUID().toString())
                .owner(owner) // 💡 User 객체 주입
                .name(name)
                .description(description)
                .path(path)
                .type(WorkspaceType.PERSONAL)
                .build();
    }

    // ✅ 팀 워크스페이스 생성
    public static Workspace createTeam(User owner, String name, String description, String path) {
        return Workspace.builder()
                .uuid(UUID.randomUUID().toString())
                .owner(owner) // 💡 User 객체 주입
                .name(name)
                .description(description)
                .path(path)
                .type(WorkspaceType.TEAM)
                .build();
    }
}