package com.myide.backend.dto.design.codegen;

import com.myide.backend.service.design.codegen.CodegenTarget;

import java.util.List;

/**
 * 만들어진 파일 하나. 아직 디스크에 쓰이지 않았다.
 *
 * 생성기는 디스크를 절대 만지지 않고 이 값만 돌려준다. 그래서 미리보기가
 * 공짜로 얻어지고, 같은 입력이면 항상 같은 결과라 두 번 생성해도 안전하다.
 *
 * sourceLabel 은 "이 파일이 설계의 무엇에서 나왔는지"를 사람 말로 적은 것이다.
 * 미리보기 목록에서 파일 이름만 보면 무엇인지 알기 어렵다.
 *
 * requirementIds / requirementLabels 는 미리보기에서 파일을 기능별로 묶는 데 쓴다.
 * 예전에는 계층(Entity / Repository / Controller)으로만 묶여서, 회원가입 하나를
 * 보려면 네 그룹을 뒤져 관련 파일을 눈으로 골라내야 했다. 테이블 여덟 개짜리
 * 설계만 해도 자바 파일이 예순 개쯤 나오므로 사실상 불가능했다.
 *
 * 묶는 열쇠는 라벨이 아니라 id 다. 라벨은 이름이 바뀌면 같이 바뀌고 같은 이름이
 * 둘일 수도 있다. 라벨은 화면에 제목으로 쓰기 위해 함께 담는다.
 *
 * 여러 기능이 함께 쓰는 파일(DDL, 라우트 표, API 호출 함수 모음)은 비워 둔다.
 * 특정 기능의 것이 아니기 때문이다. 화면은 이런 파일을 마지막 묶음에 모은다.
 */
public record GeneratedFile(
        String path,
        String content,
        CodegenTarget target,
        String sourceLabel,
        List<String> requirementIds,
        List<String> requirementLabels,
        /**
         * 사람이 직접 채워야 하는 몸통이 남아 있는지.
         *
         * 생성기가 알려 준다. 화면에서 내용에 "UnsupportedOperationException" 이 있는지
         * 뒤져 알아내면, 스텁 문구를 바꾸는 순간 표시가 조용히 사라진다.
         */
        boolean needsHandWork
) {
    public GeneratedFile {
        requirementIds = requirementIds == null ? List.of() : List.copyOf(requirementIds);
        requirementLabels = requirementLabels == null ? List.of() : List.copyOf(requirementLabels);
    }

    /** 몸통을 채울 것이 없는 보통의 파일. */
    public GeneratedFile(
            String path,
            String content,
            CodegenTarget target,
            String sourceLabel,
            List<String> requirementIds,
            List<String> requirementLabels
    ) {
        this(path, content, target, sourceLabel, requirementIds, requirementLabels, false);
    }

    /** 여러 기능이 함께 쓰는 파일. 특정 요구사항에 매이지 않는다. */
    public static GeneratedFile shared(
            String path,
            String content,
            CodegenTarget target,
            String sourceLabel
    ) {
        return new GeneratedFile(path, content, target, sourceLabel, List.of(), List.of(), false);
    }
}
