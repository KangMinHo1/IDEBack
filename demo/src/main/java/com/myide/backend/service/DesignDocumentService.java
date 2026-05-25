package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.design.DesignDocument;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.design.DesignDocumentRequest;
import com.myide.backend.dto.design.DesignDocumentResponse;
import com.myide.backend.repository.design.DesignDocumentRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DesignDocumentService {

    private final DesignDocumentRepository designDocumentRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;

    public DesignDocumentResponse getDesignDocument(String workspaceId, Long userId) {
        validateUserId(userId);

        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);

        return designDocumentRepository
                .findByWorkspace_Uuid(workspace.getUuid())
                .map(DesignDocumentResponse::from)
                .orElseGet(() -> DesignDocumentResponse.empty(workspace.getUuid()));
    }

    @Transactional
    public DesignDocumentResponse saveDesignDocument(
            String workspaceId,
            Long userId,
            DesignDocumentRequest request
    ) {
        validateUserId(userId);

        Workspace workspace = getAccessibleWorkspace(workspaceId, userId);
        User user = getUser(userId);

        DesignDocument document = designDocumentRepository
                .findByWorkspace_Uuid(workspace.getUuid())
                .orElse(null);

        if (document == null) {
            DesignDocument saved = designDocumentRepository.save(
                    DesignDocument.builder()
                            .workspace(workspace)
                            .createdBy(user)
                            .erdNodesJson(request.erdNodesJson())
                            .erdEdgesJson(request.erdEdgesJson())
                            .flowNodesJson(request.flowNodesJson())
                            .flowEdgesJson(request.flowEdgesJson())
                            .build()
            );

            return DesignDocumentResponse.from(saved);
        }

        document.update(
                request.erdNodesJson(),
                request.erdEdgesJson(),
                request.flowNodesJson(),
                request.flowEdgesJson()
        );

        return DesignDocumentResponse.from(document);
    }

    private Workspace getAccessibleWorkspace(String workspaceId, Long userId) {
        return workspaceRepository.findMyAllWorkspaces(userId)
                .stream()
                .filter(workspace -> workspace.getUuid().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "워크스페이스 접근 권한이 없습니다."
                ));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "사용자를 찾을 수 없습니다."
                ));
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "로그인이 필요합니다."
            );
        }
    }
}