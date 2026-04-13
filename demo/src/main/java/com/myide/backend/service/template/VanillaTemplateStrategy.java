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
public class VanillaTemplateStrategy implements ProjectTemplateStrategy {

    @Override
    public boolean supports(TemplateType templateType) {
        return templateType == TemplateType.VANILLA;
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language) throws IOException {
        log.info("🌐 Vanilla Web 템플릿 생성 시작");

        // 1. index.html 생성
        String htmlContent = "<!DOCTYPE html>\n" +
                "<html lang=\"ko\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>VSIDE Vanilla Web</title>\n" +
                "    <link rel=\"stylesheet\" href=\"style.css\">\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>Hello, VSIDE! 🚀</h1>\n" +
                "        <p>HTML, CSS, JavaScript가 완벽하게 연동되었습니다.</p>\n" +
                "        <button id=\"helloBtn\">클릭해보세요</button>\n" +
                "    </div>\n" +
                "    <script src=\"script.js\"></script>\n" +
                "</body>\n" +
                "</html>";
        Files.writeString(projectRoot.resolve("index.html"), htmlContent);

        // 2. style.css 생성
        String cssContent = "body {\n" +
                "    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n" +
                "    background-color: #f8f9fa;\n" +
                "    display: flex;\n" +
                "    justify-content: center;\n" +
                "    align-items: center;\n" +
                "    height: 100vh;\n" +
                "    margin: 0;\n" +
                "}\n" +
                ".container {\n" +
                "    background: white;\n" +
                "    padding: 2rem;\n" +
                "    border-radius: 12px;\n" +
                "    box-shadow: 0 10px 30px rgba(0,0,0,0.1);\n" +
                "    text-align: center;\n" +
                "}\n" +
                "button {\n" +
                "    padding: 10px 20px;\n" +
                "    background-color: #007bff;\n" +
                "    color: white;\n" +
                "    border: none;\n" +
                "    border-radius: 6px;\n" +
                "    cursor: pointer;\n" +
                "    font-weight: bold;\n" +
                "    transition: background 0.3s;\n" +
                "}\n" +
                "button:hover {\n" +
                "    background-color: #0056b3;\n" +
                "}";
        Files.writeString(projectRoot.resolve("style.css"), cssContent);

        // 3. script.js 생성
        String jsContent = "document.getElementById('helloBtn').addEventListener('click', () => {\n" +
                "    alert('JavaScript가 정상적으로 동작합니다! 🎉');\n" +
                "});";
        Files.writeString(projectRoot.resolve("script.js"), jsContent);

        log.info("🌐 Vanilla Web 템플릿(HTML/CSS/JS) 파일 세팅 완료!");
    }
}