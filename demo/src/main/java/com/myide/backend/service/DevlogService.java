package com.myide.backend.service;

import com.myide.backend.domain.*;

import com.myide.backend.domain.Devlog;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.Workspace;
import com.myide.backend.dto.devlog.*;
import com.myide.backend.exception.ApiException;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.WorkspaceRepository;
import com.myide.backend.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DevlogService {

    private final WorkspaceRepository workspaceRepository;
    private final ProjectRepository projectRepository;
    private final DevlogRepository devlogRepository;
    private final CurrentUserService currentUserService;

    /**
     * 내 워크스페이스 목록 조회
     * - 현재 로그인한 사용자가 소유한 워크스페이스만 조회
     */
    public List<WorkspaceListItemResponse> getMyWorkspaces(String q, String sort) {
        Comparator<WorkspaceListItemResponse> comparator =
                Comparator.comparing(WorkspaceListItemResponse::getLastUpdatedDate);

        if (!isOldestSort(sort)) {
            comparator = comparator.reversed();
        }

        return workspaceRepository.findByOwnerId(currentUserService.getCurrentUserId()).stream()
                .map(workspace -> {
                    List<Project> projects =
                            projectRepository.findByWorkspaceUuidOrderByUpdatedAtDesc(workspace.getUuid());

                    LocalDate lastUpdated = projects.stream()
                            .map(project -> project.getUpdatedAt().toLocalDate())
                            .max(LocalDate::compareTo)
                            .orElse(workspace.getUpdatedAt().toLocalDate());

                    return WorkspaceListItemResponse.builder()
                            .uuid(workspace.getUuid())
                            .name(workspace.getName())
                            .mode(workspace.getType() == WorkspaceType.TEAM ? "team" : "personal")
                            .teamName(workspace.getTeamName())
                            .lastUpdatedDate(lastUpdated.toString())
                            .projectCount(projects.size())
                            .build();
                })
                .filter(item -> matchesWorkspace(item, q))
                .sorted(comparator)
                .toList();
    }

    /**
     * 특정 워크스페이스 상세 조회
     * - 현재 로그인한 사용자의 워크스페이스만 허용
     */
    public WorkspaceDetailResponse getWorkspaceDetail(String workspaceId, String q, String sort) {
        Workspace workspace = getOwnedWorkspace(workspaceId);

        List<ProjectDevlogGroupResponse> projectGroups = projectRepository
                .findByWorkspaceUuidOrderByUpdatedAtDesc(workspaceId)
                .stream()
                .map(project -> toProjectGroup(project, q, sort))
                .toList();

        return WorkspaceDetailResponse.builder()
                .uuid(workspace.getUuid())
                .name(workspace.getName())
                .mode(workspace.getType() == WorkspaceType.TEAM ? "team" : "personal")
                .teamName(workspace.getTeamName())
                .projects(projectGroups)
                .build();
    }

    /**
     * 개발일지 상세 조회
     * - 내 워크스페이스 > 내 프로젝트 > 해당 개발일지 순으로 검증
     */
    public DevlogDetailResponse getDevlogDetail(String workspaceId, Long projectId, Long devlogId) {
        Project project = getOwnedProject(workspaceId, projectId);

        Devlog devlog = devlogRepository.findByIdAndProjectId(devlogId, project.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "개발일지를 찾을 수 없습니다."));

        return toDevlogDetail(workspaceId, projectId, devlog);
    }

    /**
     * 개발일지 생성
     */
    @Transactional
    public DevlogDetailResponse create(DevlogCreateRequest request) {
        Workspace workspace = getOwnedWorkspace(request.getWorkspaceId());
        Project project = getOwnedProject(request.getWorkspaceId(), request.getProjectId());

        LocalDateTime now = LocalDateTime.now();

        Devlog devlog = Devlog.builder()
                .project(project)
                .title(safeTrim(request.getTitle()))
                .summary(safeTrim(request.getSummary()))
                .content(safeTrim(request.getContent()))
                .tags(normalizeTags(request.getTagsText()))
                .createdAt(now)
                .updatedAt(now)
                .build();

        Devlog saved = devlogRepository.save(devlog);

        project.setUpdatedAt(now);
        workspace.setUpdatedAt(now);

        return toDevlogDetail(request.getWorkspaceId(), request.getProjectId(), saved);
    }

    /**
     * 개발일지 수정
     */
    @Transactional
    public DevlogDetailResponse update(Long devlogId, DevlogUpdateRequest request) {
        Workspace workspace = getOwnedWorkspace(request.getWorkspaceId());
        Project project = getOwnedProject(request.getWorkspaceId(), request.getProjectId());

        Devlog devlog = devlogRepository.findByIdAndProjectId(devlogId, project.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "개발일지를 찾을 수 없습니다."));

        LocalDateTime now = LocalDateTime.now();

        devlog.setTitle(safeTrim(request.getTitle()));
        devlog.setSummary(safeTrim(request.getSummary()));
        devlog.setContent(safeTrim(request.getContent()));
        devlog.setTags(normalizeTags(request.getTagsText()));
        devlog.setUpdatedAt(now);

        project.setUpdatedAt(now);
        workspace.setUpdatedAt(now);

        return toDevlogDetail(request.getWorkspaceId(), request.getProjectId(), devlog);
    }

    /**
     * 개발일지 삭제
     */
    @Transactional
    public void delete(String workspaceId, Long projectId, Long devlogId) {
        Workspace workspace = getOwnedWorkspace(workspaceId);
        Project project = getOwnedProject(workspaceId, projectId);

        Devlog devlog = devlogRepository.findByIdAndProjectId(devlogId, project.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "개발일지를 찾을 수 없습니다."));

        devlogRepository.delete(devlog);

        LocalDateTime now = LocalDateTime.now();
        project.setUpdatedAt(now);
        workspace.setUpdatedAt(now);
    }

    private ProjectDevlogGroupResponse toProjectGroup(Project project, String q, String sort) {
        Comparator<Devlog> comparator = Comparator.comparing(Devlog::getCreatedAt);
        if (!isOldestSort(sort)) {
            comparator = comparator.reversed();
        }

        List<DevlogItemResponse> posts = devlogRepository.findByProjectId(project.getId()).stream()
                .filter(devlog -> matchesDevlog(devlog, q))
                .sorted(comparator)
                .map(this::toDevlogItem)
                .toList();

        return ProjectDevlogGroupResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .language(project.getLanguage().name())
                .lastUpdatedDate(project.getUpdatedAt() != null ? project.getUpdatedAt().toLocalDate().toString() : null)
                .devlogCount(posts.size())
                .posts(posts)
                .build();
    }

    private DevlogItemResponse toDevlogItem(Devlog devlog) {
        return DevlogItemResponse.builder()
                .id(devlog.getId())
                .title(devlog.getTitle())
                .date(devlog.getCreatedAt() != null ? devlog.getCreatedAt().toLocalDate().toString() : null)
                .summary(devlog.getSummary())
                .tags(splitTags(devlog.getTags()))
                .build();
    }

    private DevlogDetailResponse toDevlogDetail(String workspaceId, Long projectId, Devlog devlog) {
        return DevlogDetailResponse.builder()
                .id(devlog.getId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .title(devlog.getTitle())
                .date(devlog.getCreatedAt() != null ? devlog.getCreatedAt().toLocalDate().toString() : null)
                .summary(devlog.getSummary())
                .content(devlog.getContent())
                .tags(splitTags(devlog.getTags()))
                .build();
    }

    /**
     * 현재 로그인한 사용자의 워크스페이스인지 검증
     */
    private Workspace getOwnedWorkspace(String workspaceId) {
        String userId = currentUserService.getCurrentUserId();

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "워크스페이스를 찾을 수 없습니다."));

        if (!userId.equals(workspace.getOwnerId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "본인 워크스페이스만 접근할 수 있습니다.");
        }

        return workspace;
    }

    /**
     * 현재 로그인한 사용자의 워크스페이스에 속한 프로젝트인지 검증
     */
    private Project getOwnedProject(String workspaceId, Long projectId) {
        getOwnedWorkspace(workspaceId);

        return projectRepository.findByIdAndWorkspaceUuid(projectId, workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다."));
    }

    private boolean matchesWorkspace(WorkspaceListItemResponse item, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }

        String keyword = q.toLowerCase(Locale.ROOT);

        return containsIgnoreCase(item.getName(), keyword)
                || containsIgnoreCase(item.getTeamName(), keyword);
    }

    private boolean matchesDevlog(Devlog devlog, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }

        String keyword = q.toLowerCase(Locale.ROOT);

        return containsIgnoreCase(devlog.getTitle(), keyword)
                || containsIgnoreCase(devlog.getSummary(), keyword)
                || containsIgnoreCase(devlog.getContent(), keyword)
                || containsIgnoreCase(devlog.getTags(), keyword);
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean isOldestSort(String sort) {
        return "oldest".equalsIgnoreCase(sort);
    }

    private String normalizeTags(String tagsText) {
        if (tagsText == null || tagsText.isBlank()) {
            return "";
        }

        return Arrays.stream(tagsText.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }

        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .toList();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}