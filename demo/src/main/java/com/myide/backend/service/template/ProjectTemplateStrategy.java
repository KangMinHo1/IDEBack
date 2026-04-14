package com.myide.backend.service.template;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import java.nio.file.Path;
import java.io.IOException;

// 💡 전략 패턴의 뼈대입니다. 모든 템플릿 생성기는 이 인터페이스를 구현해야 합니다.
public interface ProjectTemplateStrategy {
    boolean supports(TemplateType templateType);
    void generateTemplate(Path projectRoot, LanguageType language) throws IOException;
}