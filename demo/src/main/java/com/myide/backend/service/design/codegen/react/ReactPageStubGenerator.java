package com.myide.backend.service.design.codegen.react;

import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ScreenTransitionV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import com.myide.backend.service.design.codegen.Traceability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 화면마다 페이지 컴포넌트 하나.
 *
 * 빈 컴포넌트를 놓지 않는다. 그 화면이 어떤 요구사항에서 나왔는지, 어떤 API 를
 * 부르기로 했는지, 어디로 이동하는지를 화면 위에 그대로 그려 둔다. 그러면
 * 생성 직후에 앱을 띄워 보는 것만으로 설계한 흐름을 눌러 볼 수 있다.
 */
@Component
public class ReactPageStubGenerator implements DesignCodeGenerator {

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.REACT_PAGE;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        Map<String, String> componentNames = ReactNames.componentNames(model);
        Map<String, String> functionNames = ReactNames.functionNames(model);
        Map<String, ScreenV2> screenById = new LinkedHashMap<>();
        Map<String, ApiSpecV2> apiById = new LinkedHashMap<>();

        model.screens().forEach(screen -> screenById.put(screen.id(), screen));
        model.apis().forEach(api -> apiById.put(api.id(), api));

        List<GeneratedFile> files = new ArrayList<>();

        for (ScreenV2 screen : model.screens()) {
            if ("external".equals(screen.role())) {
                continue;
            }

            String component = componentNames.get(screen.id());

            files.add(new GeneratedFile(
                    ReactNames.pagePath(component),
                    render(model, screen, component, screenById, apiById, functionNames),
                    CodegenTarget.REACT_PAGE,
                    "화면 " + screen.name(),
                    screen.requirementIds(),
                    Traceability.requirementLabels(model, screen.requirementIds())));
        }

        return files;
    }

    private String render(DesignModelV2 model, ScreenV2 screen, String component,
                          Map<String, ScreenV2> screenById, Map<String, ApiSpecV2> apiById,
                          Map<String, String> functionNames) {
        List<ScreenTransitionV2> outgoing = model.screenTransitions().stream()
                .filter(transition -> screen.id().equals(transition.from()))
                .filter(transition -> screenById.containsKey(transition.to()))
                .toList();

        List<ApiSpecV2> apis = new ArrayList<>();
        for (String apiId : screen.apiIds()) {
            ApiSpecV2 api = apiById.get(apiId);
            if (api != null) {
                apis.add(api);
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("// 설계 관리에서 생성된 화면 뼈대입니다.\n");
        builder.append("// 화면: ").append(screen.name());

        if (!screen.key().isBlank()) {
            builder.append(" (").append(screen.key()).append(")");
        }
        builder.append("\n");

        List<String> requirements = Traceability.requirementLabels(model, screen.requirementIds());
        if (!requirements.isEmpty()) {
            builder.append("// 요구사항: ").append(String.join(", ", requirements)).append("\n");
        }

        if (screen.requiresAuth()) {
            builder.append("// 로그인이 필요한 화면입니다.\n");
        }

        builder.append("\n");

        if (!outgoing.isEmpty()) {
            builder.append("import { useNavigate } from \"react-router-dom\";\n\n");
        }

        builder.append("export default function ").append(component).append("() {\n");

        if (!outgoing.isEmpty()) {
            builder.append("  const navigate = useNavigate();\n\n");
        }

        builder.append("  return (\n");
        builder.append("    <main style={{ padding: 24 }}>\n");
        builder.append("      <h1>").append(jsxText(screen.name())).append("</h1>\n");

        if (!screen.description().isBlank()) {
            builder.append("      <p>").append(jsxText(screen.description())).append("</p>\n");
        }

        if (!apis.isEmpty()) {
            builder.append("\n      <h2>이 화면이 부르는 API</h2>\n");
            builder.append("      <ul>\n");

            for (ApiSpecV2 api : apis) {
                String function = functionNames.get(api.id());
                builder.append("        <li>")
                        .append(jsxText(api.method() + " " + api.endpoint()));

                if (function != null) {
                    builder.append(" — ").append(jsxText("designApi." + function + "()"));
                }

                builder.append("</li>\n");
            }

            builder.append("      </ul>\n");
        }

        if (!outgoing.isEmpty()) {
            builder.append("\n      <h2>이 화면에서 갈 수 있는 곳</h2>\n");

            for (ScreenTransitionV2 transition : outgoing) {
                ScreenV2 target = screenById.get(transition.to());
                String label = transition.trigger().isBlank()
                        ? target.name() + "(으)로"
                        : transition.trigger();

                builder.append("      <button type=\"button\" onClick={() => navigate(\"")
                        .append(routeOf(target)).append("\")}>")
                        .append(jsxText(label))
                        .append("</button>\n");
            }
        }

        builder.append("\n      {/* TODO: 실제 화면을 만들어 주세요. */}\n");
        builder.append("    </main>\n");
        builder.append("  );\n");
        builder.append("}\n");

        return builder.toString();
    }

    /** 설계에서 {id} 로 적은 경로 변수를 react-router 가 아는 :id 로 바꾼다. */
    static String routeOf(ScreenV2 screen) {
        String key = screen.key() == null ? "" : screen.key().trim();

        if (key.isBlank()) {
            return "/";
        }

        String route = key.replaceAll("\\{([^}/]+)}", ":$1");
        return route.startsWith("/") ? route : "/" + route;
    }

    /** 중괄호와 꺾쇠는 JSX 문법으로 읽히므로 글자로 남기지 않는다. */
    private String jsxText(String raw) {
        return raw == null ? "" : raw
                .replace("{", "(")
                .replace("}", ")")
                .replace("<", "(")
                .replace(">", ")")
                .replace("\n", " ");
    }
}
