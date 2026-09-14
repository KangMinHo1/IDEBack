package com.myide.backend.service.design.codegen.react;

import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 화면 흐름도를 그대로 라우트 표로 옮긴다.
 *
 * App.jsx 는 건드리지 않는다. 사용자가 이미 쓰고 있는 파일을 덮어쓰면
 * 코드 생성이 무서운 기능이 된다. 대신 새 파일 하나만 만들고, 어디에
 * 끼워 넣으면 되는지 파일 맨 위에 적어 둔다.
 */
@Component
public class ReactRouteGenerator implements DesignCodeGenerator {

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.REACT_ROUTE;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        if (model.screens().isEmpty()) {
            return List.of();
        }

        Map<String, String> componentNames = ReactNames.componentNames(model);

        StringBuilder imports = new StringBuilder();
        StringBuilder routes = new StringBuilder();
        Set<String> usedRoutes = new LinkedHashSet<>();

        for (ScreenV2 screen : model.screens()) {
            if ("external".equals(screen.role())) {
                continue;
            }

            String component = componentNames.get(screen.id());
            String route = ReactPageStubGenerator.routeOf(screen);

            imports.append("import ").append(component).append(" from \"../pages/")
                    .append(component).append("\";\n");

            // 같은 경로가 두 번 나오면 뒤엣것은 영원히 열리지 않는다.
            // 설계 점검이 SCR_DUP_ROUTE 로 막지만, 생성기도 조용히 넘어가지 않는다.
            if (!usedRoutes.add(route)) {
                routes.append("      {/* 경로가 겹칩니다: ").append(route).append(" (")
                        .append(component).append(") */}\n");
                continue;
            }

            routes.append("      <Route path=\"").append(route).append("\" element={<")
                    .append(component).append(" />} />");

            if (screen.isEntry()) {
                routes.append("   {/* 시작 화면 */}");
            }

            routes.append("\n");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("// 설계 관리에서 생성된 라우트 표입니다.\n");
        builder.append("//\n");
        builder.append("// 쓰는 방법: main.jsx 에서 <App /> 을 <BrowserRouter> 로 감싸고,\n");
        builder.append("// App.jsx 안에서 <AppRoutes /> 를 그려 주세요.\n");
        builder.append("//\n");
        builder.append("//   import { BrowserRouter } from \"react-router-dom\";\n");
        builder.append("//   <BrowserRouter><App /></BrowserRouter>\n");
        builder.append("//\n");
        builder.append("// react-router-dom 이 없다면 먼저 설치해야 합니다:\n");
        builder.append("//   npm install react-router-dom\n\n");
        builder.append("import { Route, Routes } from \"react-router-dom\";\n");
        builder.append(imports);
        builder.append("\nexport default function AppRoutes() {\n");
        builder.append("  return (\n");
        builder.append("    <Routes>\n");
        builder.append(routes);
        builder.append("    </Routes>\n");
        builder.append("  );\n");
        builder.append("}\n");

        return List.of(GeneratedFile.shared(
                "src/routes/AppRoutes.jsx",
                builder.toString(),
                CodegenTarget.REACT_ROUTE,
                "화면 " + usedRoutes.size() + "개"));
    }
}
