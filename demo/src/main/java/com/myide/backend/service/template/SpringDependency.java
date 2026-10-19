package com.myide.backend.service.template;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 스프링부트 작업 폴더를 만들 때 고를 수 있는 의존성.
 *
 * 사용자가 보낸 글자를 build.gradle 에 그대로 넣지 않고 이 목록에서만 고르게 한다.
 * 그대로 넣으면 build.gradle 에 아무 코드나 끼워 넣을 수 있다(gradle 파일은 그 자체가 프로그램이다).
 *
 * 이름(JPA, H2 …)은 프론트 SpringDependencyPicker 의 id 와 글자까지 같아야 한다.
 * 버전은 적지 않는다. 스프링부트 플러그인(io.spring.dependency-management)이 맞는 버전을 정해 준다.
 * 여기에 항목을 늘리면 실행 이미지(demo/Dockerfile 12번)에도 넣어야 실행할 때마다 내려받지 않는다.
 */
public enum SpringDependency {

    JPA("implementation 'org.springframework.boot:spring-boot-starter-data-jpa'"),
    H2("runtimeOnly 'com.h2database:h2'"),
    MYSQL("runtimeOnly 'com.mysql:mysql-connector-j'"),
    // Lombok 은 컴파일할 때만 쓰고, 어노테이션을 코드로 바꾸는 처리기도 따로 알려 줘야 한다.
    LOMBOK("compileOnly 'org.projectlombok:lombok'",
            "annotationProcessor 'org.projectlombok:lombok'"),
    VALIDATION("implementation 'org.springframework.boot:spring-boot-starter-validation'");

    /** 고르지 않아도 항상 들어가는 줄. 템플릿이 웹 서버라서 빠지면 실행되지 않는다. */
    public static final String WEB_LINE = "implementation 'org.springframework.boot:spring-boot-starter-web'";

    private final List<String> gradleLines;

    SpringDependency(String... gradleLines) {
        this.gradleLines = List.of(gradleLines);
    }

    public List<String> gradleLines() {
        return gradleLines;
    }

    /** 화면에서 받은 이름들. 비어 있으면 아무것도 고르지 않은 것이다. 모르는 이름은 거절한다. */
    public static Set<SpringDependency> parse(Collection<String> names) {
        Set<SpringDependency> result = EnumSet.noneOf(SpringDependency.class);
        if (names == null) {
            return result;
        }

        for (String name : names) {
            try {
                result.add(SpringDependency.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "고를 수 없는 의존성입니다: " + name);
            }
        }

        return result;
    }
}
