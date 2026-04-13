package com.myide.backend.service.template;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class ConsoleTemplateStrategy implements ProjectTemplateStrategy {

    @Override
    public boolean supports(TemplateType templateType) {
        // 💡 이 담당자는 오직 'CONSOLE' 템플릿일 때만 일합니다!
        return templateType == TemplateType.CONSOLE;
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language) throws IOException {
        log.info("💻 콘솔 템플릿 생성 시작: {}", language.name());

        // 1. LanguageType Enum에 적혀있는 기본 파일명과 코드를 가져옵니다.
        String fileName = language.getDefaultFileName();
        String code = language.getDefaultCode();

        if (fileName != null && code != null) {
            // 2. 프로젝트 폴더 안에 해당 파일을 생성하고 코드를 씁니다.
            Path filePath = projectRoot.resolve(fileName);
            Files.writeString(filePath, code);
            log.info("파일 생성 완료: {}", filePath.toString());
        } else {
            log.warn("⚠️ {} 언어의 기본 파일명이나 코드가 설정되어 있지 않습니다.", language.name());
        }
    }
}