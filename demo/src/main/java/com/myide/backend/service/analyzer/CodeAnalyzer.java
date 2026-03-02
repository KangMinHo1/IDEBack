package com.myide.backend.service.analyzer;

import com.myide.backend.dto.codemap.CodeMapResponse;

public interface CodeAnalyzer {
    // 이 분석기가 지원하는 언어인지 확인
    boolean supports(String language);

    // 프로젝트 경로를 받아서 코드맵 노드/엣지 추출
    CodeMapResponse analyze(String projectRootPath);
}