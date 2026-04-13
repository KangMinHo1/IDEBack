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
public class ReactTemplateStrategy implements ProjectTemplateStrategy {

    @Override
    public boolean supports(TemplateType templateType) {
        // 이 전문가는 'REACT' 타입일 때만 출동합니다!
        return templateType == TemplateType.REACT;
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language) throws IOException {
        log.info("⚛️ React 템플릿 생성 시작");

        // 1. 디렉토리 구조 만들기 (public, src 폴더)
        Path publicDir = projectRoot.resolve("public");
        Path srcDir = projectRoot.resolve("src");
        Files.createDirectories(publicDir);
        Files.createDirectories(srcDir);

        // 2. package.json 생성 (리액트 라이브러리 정보)
        String packageJson = "{\n" +
                "  \"name\": \"vside-react-app\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"private\": true,\n" +
                "  \"dependencies\": {\n" +
                "    \"react\": \"^18.2.0\",\n" +
                "    \"react-dom\": \"^18.2.0\",\n" +
                "    \"react-scripts\": \"5.0.1\"\n" +
                "  },\n" +
                "  \"scripts\": {\n" +
                "    \"start\": \"react-scripts start\",\n" +
                "    \"build\": \"react-scripts build\"\n" +
                "  },\n" +
                "  \"browserslist\": {\n" +
                "    \"production\": [\n" +
                "      \">0.2%\",\n" +
                "      \"not dead\",\n" +
                "      \"not op_mini all\"\n" +
                "    ],\n" +
                "    \"development\": [\n" +
                "      \"last 1 chrome version\",\n" +
                "      \"last 1 firefox version\",\n" +
                "      \"last 1 safari version\"\n" +
                "    ]\n" +
                "  }\n" +
                "}";
        Files.writeString(projectRoot.resolve("package.json"), packageJson);

        // 3. public/index.html 생성 (화면 뼈대)
        String indexHtml = "<!DOCTYPE html>\n" +
                "<html lang=\"ko\">\n" +
                "  <head>\n" +
                "    <meta charset=\"utf-8\" />\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "    <title>VSIDE React App</title>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "    <noscript>You need to enable JavaScript to run this app.</noscript>\n" +
                "    <div id=\"root\"></div>\n" +
                "  </body>\n" +
                "</html>";
        Files.writeString(publicDir.resolve("index.html"), indexHtml);

        // 4. src/index.js 생성 (리액트 시작점)
        String indexJs = "import React from 'react';\n" +
                "import ReactDOM from 'react-dom/client';\n" +
                "import App from './App';\n" +
                "\n" +
                "const root = ReactDOM.createRoot(document.getElementById('root'));\n" +
                "root.render(\n" +
                "  <React.StrictMode>\n" +
                "    <App />\n" +
                "  </React.StrictMode>\n" +
                ");";
        Files.writeString(srcDir.resolve("index.js"), indexJs);

        // 5. src/App.js 생성 (메인 컴포넌트)
        String appJs = "import React, { useState } from 'react';\n" +
                "import './App.css';\n" +
                "\n" +
                "function App() {\n" +
                "  const [count, setCount] = useState(0);\n" +
                "\n" +
                "  return (\n" +
                "    <div className=\"App\">\n" +
                "      <header className=\"App-header\">\n" +
                "        <h1>⚛️ Hello VSIDE React!</h1>\n" +
                "        <p>실시간으로 코드를 수정하고 저장(Ctrl+S)해보세요.</p>\n" +
                "        <div className=\"counter-box\">\n" +
                "          <button onClick={() => setCount(count + 1)}>\n" +
                "            클릭 횟수: {count}\n" +
                "          </button>\n" +
                "        </div>\n" +
                "      </header>\n" +
                "    </div>\n" +
                "  );\n" +
                "}\n" +
                "\n" +
                "export default App;";
        Files.writeString(srcDir.resolve("App.js"), appJs);

        // 6. src/App.css 생성 (예쁜 스타일)
        String appCss = ".App {\n" +
                "  text-align: center;\n" +
                "  font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;\n" +
                "}\n" +
                "\n" +
                ".App-header {\n" +
                "  background-color: #282c34;\n" +
                "  min-height: 100vh;\n" +
                "  display: flex;\n" +
                "  flex-direction: column;\n" +
                "  align-items: center;\n" +
                "  justify-content: center;\n" +
                "  color: white;\n" +
                "}\n" +
                "\n" +
                "button {\n" +
                "  padding: 12px 24px;\n" +
                "  font-size: 1.2rem;\n" +
                "  font-weight: bold;\n" +
                "  border: none;\n" +
                "  border-radius: 8px;\n" +
                "  background-color: #61dafb;\n" +
                "  color: #282c34;\n" +
                "  cursor: pointer;\n" +
                "  transition: transform 0.1s;\n" +
                "}\n" +
                "\n" +
                "button:active {\n" +
                "  transform: scale(0.95);\n" +
                "}";
        Files.writeString(srcDir.resolve("App.css"), appCss);

        log.info("⚛️ React 템플릿 파일 세팅 완료!");
    }
}