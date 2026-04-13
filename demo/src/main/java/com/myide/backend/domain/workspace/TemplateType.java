package com.myide.backend.domain.workspace;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TemplateType {
    // 템플릿 종류(포트번호, "실행 명령어")
    CONSOLE(0, ""), // 콘솔은 LanguageType의 명령어를 따름
    SPRING_BOOT(8080, "gradle bootRun"),
    REACT(3000, "npm install && HOST=0.0.0.0 npm start"),
    VANILLA(8000, "python3 -m http.server 8000 --bind 0.0.0.0");

    private final int defaultPort;
    private final String runCommand;
}