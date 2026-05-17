package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.design.DesignApiSpec;
import com.myide.backend.domain.design.DesignRequirement;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.design.DesignApiSpecRequest;
import com.myide.backend.dto.design.DesignApiSpecResponse;
import com.myide.backend.dto.design.DesignRequirementRequest;
import com.myide.backend.dto.design.DesignRequirementResponse;
import com.myide.backend.repository.DesignApiSpecRepository;
import com.myide.backend.repository.DesignRequirementRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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

    @Transactional
    public DesignRequirementResponse updateRequirement(
            String requirementId,
            Long userId,
            DesignRequirementRequest request
    ) {
        validateUserId(userId);

        DesignRequirement requirement = getAccessibleRequirement(requirementId, userId);

        requirement.update(
                request.category(),
                request.name(),
                request.description()
        );

        return DesignRequirementResponse.from(requirement);
    }

    @Transactional
    public void deleteRequirement(String requirementId, Long userId) {
        validateUserId(userId);

        DesignRequirement requirement = getAccessibleRequirement(requirementId, userId);
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

    @Transactional
    public DesignApiSpecResponse updateApiSpec(
            String apiSpecId,
            Long userId,
            DesignApiSpecRequest request
    ) {
        validateUserId(userId);
        validateMethod(request.method());

        DesignApiSpec apiSpec = getAccessibleApiSpec(apiSpecId, userId);

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
    public void deleteApiSpec(String apiSpecId, Long userId) {
        validateUserId(userId);

        DesignApiSpec apiSpec = getAccessibleApiSpec(apiSpecId, userId);
        apiSpecRepository.delete(apiSpec);
    }

    private DesignRequirement getAccessibleRequirement(String requirementId, Long userId) {
        DesignRequirement requirement = requirementRepository.findByUuid(requirementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요구사항을 찾을 수 없습니다."));

        getAccessibleWorkspace(requirement.getWorkspace().getUuid(), userId);

        return requirement;
    }

    private DesignApiSpec getAccessibleApiSpec(String apiSpecId, Long userId) {
        DesignApiSpec apiSpec = apiSpecRepository.findByUuid(apiSpecId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 명세서를 찾을 수 없습니다."));

        getAccessibleWorkspace(apiSpec.getWorkspace().getUuid(), userId);

        return apiSpec;
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