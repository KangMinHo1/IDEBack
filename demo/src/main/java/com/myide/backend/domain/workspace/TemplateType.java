package com.myide.backend.domain.workspace;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TemplateType {
    // 템플릿 종류(포트번호, "실행 명령어")
    CONSOLE(0, ""), // 콘솔은 LanguageType의 명령어를 따름

    SPRING_BOOT(8080, "gradle bootRun"),

    // React는 Vite 기반으로 생성
    REACT(5173, "npm install && npm run dev -- --port 5173"),
    // Next.js는 React 기반 프레임워크지만 구조와 실행 방식이 다르므로 분리
    NEXT(3000, "npm install && npm run dev -- -H 0.0.0.0"),

    VANILLA(8000, "python3 -m http.server 8000 --bind 0.0.0.0");

    private final int defaultPort;
    private final String runCommand;
}