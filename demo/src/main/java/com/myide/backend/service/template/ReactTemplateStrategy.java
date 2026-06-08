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
        return templateType == TemplateType.REACT;
    }

    @Override
    public void generateTemplate(Path projectRoot, LanguageType language) throws IOException {
        log.info("⚛️ Vite React 템플릿 생성 시작");

        Path publicDir = projectRoot.resolve("public");
        Path srcDir = projectRoot.resolve("src");

        Files.createDirectories(publicDir);
        Files.createDirectories(srcDir);

        String packageJson = """
                {
                  "name": "wevais-react-app",
                  "version": "1.0.0",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "dev": "vite --host 0.0.0.0",
                    "start": "vite --host 0.0.0.0",
                    "build": "vite build",
                    "preview": "vite preview --host 0.0.0.0"
                  },
                  "dependencies": {
                    "react": "^18.2.0",
                    "react-dom": "^18.2.0"
                  },
                  "devDependencies": {
                    "@vitejs/plugin-react": "^4.3.1",
                    "vite": "^5.4.11"
                  }
                }
                """;

        Files.writeString(projectRoot.resolve("package.json"), packageJson);

        String viteConfig = """
        import { defineConfig } from 'vite';
        import react from '@vitejs/plugin-react';

        export default defineConfig({
          plugins: [react()],
          server: {
            host: '0.0.0.0',
            port: 5173
          }
        });
        """;

        Files.writeString(projectRoot.resolve("vite.config.js"), viteConfig);

        String indexHtml = """
                <!DOCTYPE html>
                <html lang="ko">
                  <head>
                    <meta charset="UTF-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>WVAIS React App</title>
                  </head>
                  <body>
                    <div id="root"></div>
                    <script type="module" src="/src/main.jsx"></script>
                  </body>
                </html>
                """;

        Files.writeString(projectRoot.resolve("index.html"), indexHtml);

        String mainJsx = """
                import React from 'react';
                import { createRoot } from 'react-dom/client';
                import App from './App.jsx';
                import './style.css';

                createRoot(document.getElementById('root')).render(
                  <React.StrictMode>
                    <App />
                  </React.StrictMode>
                );
                """;

        Files.writeString(srcDir.resolve("main.jsx"), mainJsx);

        String appJsx = """
        import React, { useState } from 'react';

                function App() {
                  const [count, setCount] = useState(0);

                  return (
                    <main className="app">
                      <section className="card">
                        <p className="badge">Vite React Template</p>
                        <h1>WEVAIS React App</h1>
                        <p className="description">
                          React 기반 프론트엔드 프로젝트입니다. 파일을 수정하고 저장하면 화면에 바로 반영됩니다.
                        </p>

                        <button onClick={() => setCount(count + 1)}>
                          클릭 횟수: {count}
                        </button>
                      </section>
                    </main>
                  );
                }

                export default App;
                """;

        Files.writeString(srcDir.resolve("App.jsx"), appJsx);

        String styleCss = """
                * {
                  box-sizing: border-box;
                }

                body {
                  margin: 0;
                  font-family: Arial, Helvetica, sans-serif;
                  background: #f3f6fb;
                  color: #111827;
                }

                .app {
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  padding: 40px;
                }

                .card {
                  width: min(520px, 100%);
                  padding: 40px;
                  border-radius: 24px;
                  background: white;
                  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.12);
                  text-align: center;
                }

                .badge {
                  display: inline-flex;
                  margin: 0 0 16px;
                  padding: 6px 12px;
                  border-radius: 999px;
                  background: #eef2ff;
                  color: #4f46e5;
                  font-size: 13px;
                  font-weight: 700;
                }

                h1 {
                  margin: 0;
                  font-size: 36px;
                  letter-spacing: -0.04em;
                }

                .description {
                  margin: 16px 0 28px;
                  color: #6b7280;
                  line-height: 1.6;
                }

                button {
                  border: none;
                  border-radius: 12px;
                  padding: 12px 20px;
                  background: #2563eb;
                  color: white;
                  font-size: 15px;
                  font-weight: 700;
                  cursor: pointer;
                }

                button:hover {
                  background: #1d4ed8;
                }
                """;

        Files.writeString(srcDir.resolve("style.css"), styleCss);

        String gitignore = """
                node_modules
                dist
                .env
                .env.local
                """;

        Files.writeString(projectRoot.resolve(".gitignore"), gitignore);

        log.info("⚛️ Vite React 템플릿 파일 세팅 완료");
    }
}