package com.myide.backend.config;

import com.myide.backend.domain.*;
import com.myide.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDateTime;

@Profile("local")
@Configuration
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final DevlogRepository devlogRepository;

    @Override
    public void run(String... args) {
        if (workspaceRepository.count() > 0) return;

        Workspace personal = Workspace.builder()
                .uuid("ws-personal-001")
                .name("My Personal Workspace")
                .ownerId("user-001")
                .description("개인 작업용 워크스페이스")
                .path("D:\\MyWork\\PersonalWorkspace")
                .type(WorkspaceType.PERSONAL)
                .updatedAt(LocalDateTime.now())
                .build();

        Workspace team = Workspace.builder()
                .uuid("ws-team-001")
                .name("Team Alpha Workspace")
                .ownerId("user-001")
                .description("팀 작업용 워크스페이스")
                .path("D:\\MyWork\\TeamWorkspace")
                .type(WorkspaceType.TEAM)
                .teamName("Team Alpha")
                .updatedAt(LocalDateTime.now())
                .build();

        workspaceRepository.save(personal);
        workspaceRepository.save(team);

        workspaceMemberRepository.save(
                WorkspaceMember.builder()
                        .workspace(personal)
                        .userId("user-001")
                        .build()
        );

        workspaceMemberRepository.save(
                WorkspaceMember.builder()
                        .workspace(team)
                        .userId("user-001")
                        .build()
        );

        Project p1 = projectRepository.save(Project.builder()
                .name("Portfolio Website")
                .description("포트폴리오 웹 프로젝트")
                .language(LanguageType.JAVA)
                .gitUrl("https://github.com/example/portfolio")
                .workspace(personal)
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build());

        Project p2 = projectRepository.save(Project.builder()
                .name("VSIDE UI")
                .description("웹 IDE 프론트")
                .language(LanguageType.JAVASCRIPT)
                .gitUrl("https://github.com/example/vside-ui")
                .workspace(personal)
                .updatedAt(LocalDateTime.now().minusDays(2))
                .build());

        devlogRepository.save(Devlog.builder()
                .project(p1)
                .title("프로젝트 초기 설정 및 환경 구성")
                .summary("개발 환경 설정 완료, 기본 스택 구성")
                .content("프로젝트 초기 설정 작업을 완료했습니다.")
                .tags("Setup,Configuration")
                .createdAt(LocalDateTime.now().minusDays(10))
                .updatedAt(LocalDateTime.now().minusDays(10))
                .build());

        devlogRepository.save(Devlog.builder()
                .project(p2)
                .title("UI/UX 디자인 시스템 구축")
                .summary("컬러 팔레트, 타이포그래피, 컴포넌트 라이브러리 정의")
                .content("UI/UX 디자인 시스템 구축 작업을 진행했습니다.")
                .tags("Design,UI/UX")
                .createdAt(LocalDateTime.now().minusDays(9))
                .updatedAt(LocalDateTime.now().minusDays(9))
                .build());
    }
}