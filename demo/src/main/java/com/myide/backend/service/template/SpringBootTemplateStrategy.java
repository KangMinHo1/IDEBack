package com.myide.backend.service.template;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

@Component
public class SpringBootTemplateStrategy implements ProjectTemplateStrategy {
    @Override
    public boolean supports(TemplateType templateType) {
        return templateType == TemplateType.SPRING_BOOT;
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language) throws IOException {
        Files.writeString(projectRoot.resolve("build.gradle"),
                "plugins { id 'org.springframework.boot' version '3.2.0'; id 'io.spring.dependency-management' version '1.1.4'; id 'java' }\n" +
                        "repositories { mavenCentral() }\n" +
                        "dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }\n"
        );
        Path srcPath = projectRoot.resolve("src/main/java/com/example/demo");
        Files.createDirectories(srcPath);
        Files.writeString(srcPath.resolve("DemoApplication.java"),
                "package com.example.demo;\n" +
                        "import org.springframework.boot.SpringApplication;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n" +
                        "@SpringBootApplication\npublic class DemoApplication { public static void main(String[] args) { SpringApplication.run(DemoApplication.class, args); } }"
        );
    }
}