package com.myide.backend.service;

import com.myide.backend.domain.Devlog;
import com.myide.backend.domain.Project;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.domain.workspace.WorkspaceType;
import com.myide.backend.dto.devlog.*;
import com.myide.backend.exception.ApiException;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

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

    public List<WorkspaceListItemResponse> getMyWorkspaces(String q, String sort) {
        Comparator<WorkspaceListItemResponse> comparator =
                Comparator.comparing(WorkspaceListItemResponse::getLastUpdatedDate);

        if (!isOldestSort(sort)) {
            comparator = comparator.reversed();
        }

        Long userId = currentUserService.getCurrentUserId();

        return workspaceRepository.findMyAllWorkspaces(userId).stream()
                .map(workspace -> {
                    List<Project> projects =
                            projectRepository.findByWorkspaceUuidOrderByUpdatedAtDesc(workspace.getUuid());

                    LocalDate lastUpdated = projects.stream()
                            .map(Project::getUpdatedAt)
                            .filter(java.util.Objects::nonNull)
                            .map(LocalDateTime::toLocalDate)
                            .max(LocalDate::compareTo)
                            .orElse(
                                    workspace.getUpdatedAt() != null
                                            ? workspace.getUpdatedAt().toLocalDate()
                                            : LocalDate.now()
                            );

                    int devlogCount = projects.stream()
                            .mapToInt(project -> devlogRepository.findByProjectId(project.getId()).size())
                            .sum();

                    return WorkspaceListItemResponse.builder()
                            .uuid(workspace.getUuid())
                            .name(workspace.getName())
                            .mode(workspace.getType() == WorkspaceType.TEAM ? "team" : "personal")
                            .lastUpdatedDate(lastUpdated.toString())
                            .devlogCount(devlogCount)
                            .build();
                })
                .filter(item -> matchesWorkspace(item, q))
                .sorted(comparator)
                .toList();
    }

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
                .projects(projectGroups)
                .build();
    }

    public DevlogDetailResponse getDevlogDetail(String workspaceId, Long projectId, Long devlogId) {
        Project project = getOwnedProject(workspaceId, projectId);

        Devlog devlog = devlogRepository.findByIdAndProjectId(devlogId, project.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "개발일지를 찾을 수 없습니다."));

        return toDevlogDetail(workspaceId, projectId, devlog);
    }

    @Transactional
    public DevlogDetailResponse create(DevlogCreateRequest request) {
        Workspace workspace = getOwnedWorkspace(request.getWorkspaceId());
        Project project = getOwnedProject(request.getWorkspaceId(), request.getProjectId());

        Devlog devlog = Devlog.builder()
                .project(project)
                .title(request.getTitle().trim())
                .summary(request.getSummary().trim())
                .content(request.getContent().trim())
                .tags(normalizeTags(request.getTagsText()))
                .date(request.getDate() != null ? request.getDate() : LocalDate.now())
                .stage(
                        request.getStage() != null && !request.getStage().isBlank()
                                ? request.getStage().trim()
                                : "implementation"
                )
                .goal(safeTrim(request.getGoal()))
                .design(safeTrim(request.getDesign()))
                .issue(safeTrim(request.getIssue()))
                .solution(safeTrim(request.getSolution()))
                .nextPlan(safeTrim(request.getNextPlan()))
                .commitHash(safeTrim(request.getCommitHash()))
                .progress(request.getProgress() != null ? request.getProgress() : 0)
                .build();

        devlogRepository.save(devlog);

        LocalDateTime now = LocalDateTime.now();
        project.setUpdatedAt(now);
        workspace.setUpdatedAt(now);

        return toDevlogDetail(request.getWorkspaceId(), request.getProjectId(), devlog);
    }

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
        devlog.setDate(request.getDate() != null ? request.getDate() : devlog.getDate());

        String nextStage = safeTrim(request.getStage());
        devlog.setStage(nextStage.isBlank() ? devlog.getStage() : nextStage);

        devlog.setGoal(safeTrim(request.getGoal()));
        devlog.setDesign(safeTrim(request.getDesign()));
        devlog.setIssue(safeTrim(request.getIssue()));
        devlog.setSolution(safeTrim(request.getSolution()));
        devlog.setNextPlan(safeTrim(request.getNextPlan()));
        devlog.setCommitHash(safeTrim(request.getCommitHash()));
        devlog.setProgress(request.getProgress() != null ? request.getProgress() : 0);
        devlog.setUpdatedAt(now);

        project.setUpdatedAt(now);
        workspace.setUpdatedAt(now);

        return toDevlogDetail(request.getWorkspaceId(), request.getProjectId(), devlog);
    }

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
                .lastUpdatedDate(
                        project.getUpdatedAt() != null
                                ? project.getUpdatedAt().toLocalDate().toString()
                                : null
                )
                .devlogCount(posts.size())
                .posts(posts)
                .build();
    }

    private DevlogItemResponse toDevlogItem(Devlog devlog) {
        return DevlogItemResponse.builder()
                .id(devlog.getId())
                .title(devlog.getTitle())
                .date(devlog.getDate() != null ? devlog.getDate().toString() : null)
                .summary(devlog.getSummary())
                .content(devlog.getContent())
                .tags(splitTags(devlog.getTags()))
                .stage(devlog.getStage())
                .goal(devlog.getGoal())
                .design(devlog.getDesign())
                .issue(devlog.getIssue())
                .solution(devlog.getSolution())
                .nextPlan(devlog.getNextPlan())
                .commitHash(devlog.getCommitHash())
                .progress(devlog.getProgress())
                .build();
    }

    private DevlogDetailResponse toDevlogDetail(String workspaceId, Long projectId, Devlog devlog) {
        return DevlogDetailResponse.builder()
                .id(devlog.getId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .title(devlog.getTitle())
                .date(devlog.getDate() != null ? devlog.getDate().toString() : null)
                .summary(devlog.getSummary())
                .content(devlog.getContent())
                .tags(splitTags(devlog.getTags()))
                .stage(devlog.getStage())
                .goal(devlog.getGoal())
                .design(devlog.getDesign())
                .issue(devlog.getIssue())
                .solution(devlog.getSolution())
                .nextPlan(devlog.getNextPlan())
                .commitHash(devlog.getCommitHash())
                .progress(devlog.getProgress())
                .build();
    }

    private Workspace getOwnedWorkspace(String workspaceId) {
        Long userId = currentUserService.getCurrentUserId();

        return workspaceRepository.findByUuidAndOwner_Id(workspaceId, userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "워크스페이스를 찾을 수 없거나 접근 권한이 없습니다."
                ));
    }

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
        return containsIgnoreCase(item.getName(), keyword);
    }

    private boolean matchesDevlog(Devlog devlog, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }

        String keyword = q.toLowerCase(Locale.ROOT);

        return containsIgnoreCase(devlog.getTitle(), keyword)
                || containsIgnoreCase(devlog.getSummary(), keyword)
                || containsIgnoreCase(devlog.getContent(), keyword)
                || containsIgnoreCase(devlog.getTags(), keyword)
                || containsIgnoreCase(devlog.getGoal(), keyword)
                || containsIgnoreCase(devlog.getDesign(), keyword)
                || containsIgnoreCase(devlog.getIssue(), keyword)
                || containsIgnoreCase(devlog.getSolution(), keyword)
                || containsIgnoreCase(devlog.getNextPlan(), keyword)
                || containsIgnoreCase(devlog.getCommitHash(), keyword)
                || containsIgnoreCase(devlog.getStage(), keyword);
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