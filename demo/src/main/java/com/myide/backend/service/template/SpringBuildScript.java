package com.myide.backend.service.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스프링부트 build.gradle 을 만들고, 빠진 의존성만 덧붙인다.
 *
 * 만들기는 작업 폴더를 만들 때(SpringBootTemplateStrategy), 덧붙이기는 설계 코드 생성이
 * JPA·Lombok 을 쓰는 코드를 넣을 때(DesignCodegenService) 쓴다. 덧붙일 때는 사용자가 직접
 * 넣은 의존성과 설정을 한 글자도 바꾸지 않는다. 이미 있는 줄은 다시 넣지 않는다.
 */
public final class SpringBuildScript {

    private static final Pattern LINE = Pattern.compile("^(\\w+)\\s+'([^':]+):([^':]+)'$");

    private SpringBuildScript() {
    }

    public static String render(Set<SpringDependency> dependencies) {
        StringBuilder builder = new StringBuilder()
                .append("plugins {\n")
                .append("    id 'org.springframework.boot' version '3.2.0'\n")
                .append("    id 'io.spring.dependency-management' version '1.1.4'\n")
                .append("    id 'java'\n")
                .append("}\n\n")
                .append("repositories {\n")
                .append("    mavenCentral()\n")
                .append("}\n\n")
                .append("dependencies {\n")
                .append("    ").append(SpringDependency.WEB_LINE).append("\n");

        // enum 선언 순서대로 넣어 고른 순서와 상관없이 같은 파일이 나오게 한다.
        for (SpringDependency dependency : SpringDependency.values()) {
            if (dependencies.contains(dependency)) {
                for (String line : dependency.gradleLines()) {
                    builder.append("    ").append(line).append("\n");
                }
            }
        }

        return builder.append("}\n").toString();
    }

    /** 이 줄과 같은 의존성이 이미 있는지. 따옴표 종류, 괄호, 뒤에 붙은 버전은 따지지 않는다. */
    public static boolean contains(String script, String line) {
        Matcher parts = LINE.matcher(line);
        if (!parts.matches()) {
            throw new IllegalArgumentException("의존성 줄 형식이 아닙니다: " + line);
        }

        Pattern existing = Pattern.compile("\\b" + Pattern.quote(parts.group(1))
                + "\\s*\\(?\\s*['\"]" + Pattern.quote(parts.group(2) + ":" + parts.group(3))
                + "(:[^'\"]*)?['\"]");
        return existing.matcher(script).find();
    }

    /** 빠진 줄을 맨 바깥 dependencies 블록 끝에 덧붙인 내용. 빠진 것이 없으면 원래 내용 그대로. */
    public static String addMissing(String script, List<String> lines) {
        List<String> missing = new ArrayList<>();
        for (String line : lines) {
            if (!contains(script, line) && !missing.contains(line)) {
                missing.add(line);
            }
        }

        if (missing.isEmpty()) {
            return script;
        }

        StringBuilder added = new StringBuilder();
        for (String line : missing) {
            added.append("    ").append(line).append("\n");
        }

        int close = topLevelDependenciesEnd(script);
        if (close < 0) {
            String separator = script.isEmpty() || script.endsWith("\n") ? "\n" : "\n\n";
            return script + separator + "dependencies {\n" + added + "}\n";
        }

        // 한 줄짜리 블록(dependencies { implementation '...' })도 있어서 닫는 괄호 앞을 줄바꿈으로 맞춘다.
        String before = script.substring(0, close).replaceAll("[ \\t]+$", "");
        if (!before.endsWith("\n")) {
            before += "\n";
        }
        return before + added + script.substring(close);
    }

    /**
     * 맨 바깥(buildscript { } 안이 아닌) dependencies 블록의 닫는 괄호 위치. 없으면 -1.
     * buildscript 안의 dependencies 는 gradle 플러그인용이라 거기에 넣으면 앱이 라이브러리를 못 찾는다.
     */
    private static int topLevelDependenciesEnd(String script) {
        Matcher start = Pattern.compile("\\bdependencies\\s*\\{").matcher(script);

        while (start.find()) {
            if (depthAt(script, start.start()) != 0) {
                continue;
            }

            int depth = 0;
            for (int i = start.end() - 1; i < script.length(); i++) {
                char c = script.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        return -1;
    }

    private static int depthAt(String script, int position) {
        int depth = 0;
        for (int i = 0; i < position; i++) {
            char c = script.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }
}
