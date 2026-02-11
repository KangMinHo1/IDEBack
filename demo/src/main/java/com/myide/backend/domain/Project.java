package com.myide.backend.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;        // 프로젝트 이름 (예: Java-Web-Server)

    @Column(nullable = false)
    private String description; // 설명

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LanguageType language; // 언어 (JAVA, PYTHON ...)

    @Column(name = "git_url")
    private String gitUrl;      // 연결된 깃허브 주소 (https://github.com/...)

    // [핵심] N:1 관계 설정 (여러 프로젝트가 하나의 워크스페이스에 소속됨)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id") // DB 컬럼명
    @JsonIgnore // 순환 참조 방지
    private Workspace workspace;

    // 편의 메서드: 프로젝트 폴더명 생성 (ID 대신 이름 사용 시 중복 방지 로직 필요하지만 일단 심플하게)
    public String getFolderName() {
        return this.name;
    }
}