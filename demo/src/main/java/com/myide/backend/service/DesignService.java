package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.design.DesignApiSpec;
import com.myide.backend.domain.design.DesignRequirement;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.design.DesignApiSpecRequest;
import com.myide.backend.dto.design.DesignApiSpecResponse;
import com.myide.backend.dto.design.DesignRequirementRequest;
import com.myide.backend.dto.design.DesignRequirementResponse;
import com.myide.backend.repository.design.DesignApiSpecRepository;
import com.myide.backend.repository.design.DesignRequirementRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DesignService {

    private final DesignRequirementRepository requirementRepository;
    private final DesignApiSpecRepository apiSpecRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    public List<DesignRequirementResponse> getRequirements(String workspaceId, Long userId) {
        validateUserId(userId);
        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

        return requirementRepository
                .findByWorkspace_UuidOrderByCreatedAtAsc(workspace.getUuid())
                .stream()
                .map(DesignRequirementResponse::from)
                .toList();
    }

    @Transactional
    public DesignRequirementResponse createRequirement(
            String workspaceId,
            Long userId,
            DesignRequirementRequest request
    ) {
        validateUserId(userId);

        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);
        User user = getUser(userId);

        DesignRequirement requirement = DesignRequirement.builder()
                .workspace(workspace)
                .createdBy(user)
                .category(request.category())
                .name(request.name())
                .description(request.description())
                .build();

        DesignRequirement saved = requirementRepository.save(requirement);

        return DesignRequirementResponse.from(saved);
    }

    /**
     * workspaceId 는 null 일 수 있다(옛 경로 /api/design/requirements/{id}).
     * 그때의 처리는 getAccessibleRequirement 참고.
     */
    @Transactional
    public DesignRequirementResponse updateRequirement(
            String workspaceId,
            String requirementId,
            Long userId,
            DesignRequirementRequest request
    ) {
        validateUserId(userId);

        DesignRequirement requirement = getAccessibleRequirement(workspaceId, requirementId, userId);

        requirement.update(
                request.category(),
                request.name(),
                request.description()
        );

        return DesignRequirementResponse.from(requirement);
    }

    @Transactional
    public void deleteRequirement(String workspaceId, String requirementId, Long userId) {
        validateUserId(userId);

        DesignRequirement requirement = getAccessibleRequirement(workspaceId, requirementId, userId);
        requirementRepository.delete(requirement);
    }

    public List<DesignApiSpecResponse> getApiSpecs(String workspaceId, Long userId) {
        validateUserId(userId);
        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

        return apiSpecRepository
                .findByWorkspace_UuidOrderByCreatedAtAsc(workspace.getUuid())
                .stream()
                .map(DesignApiSpecResponse::from)
                .toList();
    }

    @Transactional
    public DesignApiSpecResponse createApiSpec(
            String workspaceId,
            Long userId,
            DesignApiSpecRequest request
    ) {
        validateUserId(userId);
        validateMethod(request.method());

        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);
        User user = getUser(userId);

        DesignApiSpec apiSpec = DesignApiSpec.builder()
                .workspace(workspace)
                .createdBy(user)
                .method(request.method())
                .endpoint(request.endpoint())
                .description(request.description())
                .request(request.request())
                .response(request.response())
                .build();

        DesignApiSpec saved = apiSpecRepository.save(apiSpec);

        return DesignApiSpecResponse.from(saved);
    }

    /** workspaceId 는 null 일 수 있다(옛 경로). updateRequirement 참고. */
    @Transactional
    public DesignApiSpecResponse updateApiSpec(
            String workspaceId,
            String apiSpecId,
            Long userId,
            DesignApiSpecRequest request
    ) {
        validateUserId(userId);
        validateMethod(request.method());

        DesignApiSpec apiSpec = getAccessibleApiSpec(workspaceId, apiSpecId, userId);

        apiSpec.update(
                request.method(),
                request.endpoint(),
                request.description(),
                request.request(),
                request.response()
        );

        return DesignApiSpecResponse.from(apiSpec);
    }

    @Transactional
    public void deleteApiSpec(String workspaceId, String apiSpecId, Long userId) {
        validateUserId(userId);

        DesignApiSpec apiSpec = getAccessibleApiSpec(workspaceId, apiSpecId, userId);
        apiSpecRepository.delete(apiSpec);
    }

    /*
     * 요구사항·API 명세의 uuid 는 워크스페이스 안에서만 유일하다. 프로젝트를 복제하면
     * 원본과 사본이 같은 uuid 를 가지므로(설계 문서의 항목 id 를 그대로 쓰기 때문)
     * uuid 하나만으로는 어느 행인지 정할 수 없다.
     *
     * - workspaceId 가 있으면 그 워크스페이스에서만 찾는다.
     * - 없으면(옛 경로) 내가 접근할 수 있는 워크스페이스의 행만 추린다. 하나면 그것을
     *   쓰고, 둘 이상이면 원본·사본을 모두 가진 사용자라 어느 쪽인지 알 수 없으므로
     *   임의로 고르지 않고 409 로 워크스페이스 경로를 쓰라고 알린다.
     */
    private DesignRequirement getAccessibleRequirement(String workspaceId, String requirementId, Long userId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

            return requirementRepository.findByUuidAndWorkspace_Uuid(requirementId, workspace.getUuid())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요구사항을 찾을 수 없습니다."));
        }

        List<DesignRequirement> candidates = requirementRepository.findAllByUuid(requirementId);

        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "요구사항을 찾을 수 없습니다.");
        }

        return pickAccessible(candidates, row -> row.getWorkspace().getUuid(), userId, "요구사항");
    }

    private DesignApiSpec getAccessibleApiSpec(String workspaceId, String apiSpecId, Long userId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

            return apiSpecRepository.findByUuidAndWorkspace_Uuid(apiSpecId, workspace.getUuid())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 명세서를 찾을 수 없습니다."));
        }

        List<DesignApiSpec> candidates = apiSpecRepository.findAllByUuid(apiSpecId);

        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API 명세서를 찾을 수 없습니다.");
        }

        return pickAccessible(candidates, row -> row.getWorkspace().getUuid(), userId, "API 명세서");
    }

    private <T> T pickAccessible(
            List<T> candidates,
            Function<T, String> workspaceOf,
            Long userId,
            String label
    ) {
        Set<String> accessible = workspaceRepository.findMyAllWorkspaces(userId)
                .stream()
                .map(Workspace::getUuid)
                .collect(Collectors.toSet());

        List<T> mine = candidates.stream()
                .filter(row -> accessible.contains(workspaceOf.apply(row)))
                .toList();

        if (mine.isEmpty()) {
            // 예전과 같은 응답: 행은 있지만 내 워크스페이스가 아니다.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 접근 권한이 없습니다.");
        }

        if (mine.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "같은 ID 의 " + label + "이(가) 여러 프로젝트에 있습니다. "
                            + "/api/workspaces/{workspaceId}/design/... 경로로 요청해주세요.");
        }

        return mine.get(0);
    }

    private Workspace getAccessibleWorkspace(String workspaceId, Long userId) {
        return workspaceRepository.findMyAllWorkspaces(userId)
                .stream()
                .filter(workspace -> workspace.getUuid().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "워크스페이스 접근 권한이 없습니다."));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
    }

    private void validateMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP Method는 필수입니다.");
        }

        String upperMethod = method.toUpperCase();

        if (
                !upperMethod.equals("GET") &&
                        !upperMethod.equals("POST") &&
                        !upperMethod.equals("PUT") &&
                        !upperMethod.equals("PATCH") &&
                        !upperMethod.equals("DELETE")
        ) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 HTTP Method입니다.");
        }
    }
}