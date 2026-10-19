package com.myide.backend.service.template;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

@Component
public class SpringBootTemplateStrategy implements ProjectTemplateStrategy {
    @Override
    public boolean supports(TemplateType templateType) {
        return templateType == TemplateType.SPRING_BOOT;
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language) throws IOException {
        generateTemplate(projectRoot, language, Set.of());
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language,
                                 Set<SpringDependency> dependencies) throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"), SpringBuildScript.render(dependencies));
        Path srcPath = projectRoot.resolve("src/main/java/com/example/demo");
        Files.createDirectories(srcPath);
        Files.writeString(srcPath.resolve("DemoApplication.java"),
                "package com.example.demo;\n" +
                        "import org.springframework.boot.SpringApplication;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n" +
                        "@SpringBootApplication\npublic class DemoApplication { public static void main(String[] args) { SpringApplication.run(DemoApplication.class, args); } }"
        );

        // JPA 를 고르면 DB 설정이 없을 때 서버가 뜨다가 죽으므로, 고른 DB 에 맞춘 설정을 같이 만든다.
        Optional<String> appConfig = SpringAppConfig.render(dependencies);
        if (appConfig.isPresent()) {
            Path configPath = projectRoot.resolve(SpringAppConfig.PATH);
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, appConfig.get());
        }
    }
}
