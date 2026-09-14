package com.myide.backend.service.design.codegen.react;

import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import com.myide.backend.service.design.codegen.NameMapper;
import com.myide.backend.service.design.codegen.Traceability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 명세마다 호출 함수 하나.
 *
 * 생성되는 프로젝트는 이 저장소가 아니라 사용자의 워크스페이스 안에 있는
 * 독립된 Vite React 앱이다. 그래서 여기 있는 공용 클라이언트를 import 하지
 * 않고, 그 프로젝트 안에서 홀로 돌아가는 파일을 만든다.
 */
@Component
public class ReactApiClientGenerator implements DesignCodeGenerator {

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}|:([A-Za-z0-9_]+)");

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.REACT_API_CLIENT;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        if (model.apis().isEmpty()) {
            return List.of();
        }

        Map<String, String> functionNames = ReactNames.functionNames(model);

        StringBuilder builder = new StringBuilder();
        builder.append("// 설계 관리에서 생성된 API 호출 함수입니다.\n");
        builder.append("// 다시 생성하면 이 파일은 덮어써집니다.\n\n");
        builder.append("const BASE_URL = import.meta.env?.VITE_API_BASE_URL ?? \"http://localhost:8080\";\n\n");
        builder.append(requestHelper());

        for (ApiSpecV2 api : model.apis()) {
            String name = functionNames.get(api.id());
            if (name == null) {
                continue;
            }
            builder.append(renderFunction(model, api, name));
        }

        return List.of(GeneratedFile.shared(
                "src/api/designApi.js",
                builder.toString(),
                CodegenTarget.REACT_API_CLIENT,
                "API " + functionNames.size() + "개"));
    }

    private String requestHelper() {
        return """
                /**
                 * 모든 호출이 지나가는 곳.
                 *
                 * 로그인 토큰을 붙이는 방식이 프로젝트마다 다르므로 한 곳에서만
                 * 고치면 되도록 모아 두었습니다.
                 */
                async function request(method, path, body) {
                  const response = await fetch(`${BASE_URL}${path}`, {
                    method,
                    headers: { "Content-Type": "application/json" },
                    credentials: "include",
                    body: body === undefined ? undefined : JSON.stringify(body),
                  });

                  if (!response.ok) {
                    const message = await response.text().catch(() => "");
                    throw new Error(message || `요청 실패 (${response.status})`);
                  }

                  if (response.status === 204) return null;

                  const text = await response.text();
                  return text ? JSON.parse(text) : null;
                }

                """;
    }

    private String renderFunction(DesignModelV2 model, ApiSpecV2 api, String name) {
        List<String> params = new ArrayList<>();
        String template = api.endpoint();

        Matcher matcher = PATH_PARAM.matcher(api.endpoint());
        StringBuilder path = new StringBuilder();
        int last = 0;

        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String param = NameMapper.toParamName(raw);

            params.add(param);
            path.append(template, last, matcher.start()).append("${").append(param).append("}");
            last = matcher.end();
        }

        path.append(template.substring(last));

        boolean body = "POST".equals(api.method()) || "PUT".equals(api.method())
                || "PATCH".equals(api.method());

        if (body) {
            params.add("body");
        }

        StringBuilder builder = new StringBuilder("/**\n");
        builder.append(" * ").append(api.description().isBlank()
                ? api.method() + " " + api.endpoint()
                : api.description().replace("*/", "*")).append("\n");

        List<String> requirements = Traceability.requirementLabels(model, api.requirementIds());
        if (!requirements.isEmpty()) {
            builder.append(" *\n * 요구사항: ").append(String.join(", ", requirements)).append("\n");
        }

        builder.append(" */\n");
        builder.append("export function ").append(name).append("(")
                .append(String.join(", ", params)).append(") {\n");
        builder.append("  return request(\"").append(api.method()).append("\", `")
                .append(path).append("`");

        if (body) {
            builder.append(", body");
        }

        builder.append(");\n}\n\n");

        return builder.toString();
    }
}
