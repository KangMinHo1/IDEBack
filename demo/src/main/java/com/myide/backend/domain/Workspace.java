package com.myide.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {

    @Id
    private String uuid;      // 워크스페이스 고유 ID (폴더명)
    private String name;      // 워크스페이스 이름 (Team-Workspace-001)
    private String ownerId;   // 생성자 ID
    private String description;

    // [New] 워크스페이스가 저장된 실제 서버 경로 (예: D:\MyWork\JavaStudy)
    @Column(nullable = false)
    private String path;

    // 1:N 관계 추가 (프로젝트 목록)
    @OneToMany(mappedBy = "workspace", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Project> projects = new ArrayList<>();
}