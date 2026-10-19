package com.myide.backend.service.template;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Set;

// 💡 전략 패턴의 뼈대입니다. 모든 템플릿 생성기는 이 인터페이스를 구현해야 합니다.
public interface ProjectTemplateStrategy {
    boolean supports(TemplateType templateType);
    void generateTemplate(Path projectRoot, LanguageType language) throws IOException;

    // 작업 폴더를 만들 때 고른 의존성. 지금은 스프링부트만 쓰고, 다른 템플릿은 무시한다.
    default void generateTemplate(Path projectRoot, LanguageType language,
                                  Set<SpringDependency> dependencies) throws IOException {
        generateTemplate(projectRoot, language);
    }
}