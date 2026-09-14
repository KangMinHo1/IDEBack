package com.myide.backend.dto.design.codegen;

import java.util.List;

/**
 * 미리보기 목록에 뜨는 파일 하나.
 *
 * existingContent 를 함께 보내는 이유는 화면에서 바뀐 곳을 보여 주기
 * 위해서다. 무엇이 덮어써지는지 보지 않고 결정하게 하면 안 된다.
 *
 * requirementIds / requirementLabels 는 화면이 파일을 기능별로 묶는 데 쓴다.
 * 계층(Entity / Repository / Controller)으로만 묶으면 기능 하나에 관련된 파일을
 * 찾을 수 없다. 비어 있으면 여러 기능이 함께 쓰는 파일이거나 연결이 빠진 파일이다.
 */
public record CodegenFileView(
        String path,
        String content,
        CodegenFileStatus status,
        String existingHash,
        String existingContent,
        String target,
        String targetLabel,
        String sourceLabel,
        List<String> requirementIds,
        List<String> requirementLabels,
        /** 사람이 직접 채워야 하는 몸통이 남아 있는지. 미리보기에 표시한다. */
        boolean needsHandWork
) {
    public CodegenFileView {
        requirementIds = requirementIds == null ? List.of() : List.copyOf(requirementIds);
        requirementLabels = requirementLabels == null ? List.of() : List.copyOf(requirementLabels);
    }
}
