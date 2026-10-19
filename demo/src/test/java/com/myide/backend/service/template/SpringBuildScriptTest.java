package com.myide.backend.service.template;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringBuildScriptTest {

    private static final String OLD_TEMPLATE =
            "plugins { id 'org.springframework.boot' version '3.2.0'; id 'io.spring.dependency-management' version '1.1.4'; id 'java' }\n"
                    + "repositories { mavenCentral() }\n"
                    + "dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' }\n";

    private static final List<String> JPA_AND_LOMBOK = List.of(
            SpringDependency.JPA.gradleLines().get(0),
            SpringDependency.LOMBOK.gradleLines().get(0),
            SpringDependency.LOMBOK.gradleLines().get(1));

    @Test
    void 아무것도_안_고르면_웹만_들어간다() {
        String script = SpringBuildScript.render(Set.of());

        assertThat(script).contains(SpringDependency.WEB_LINE);
        assertThat(script).doesNotContain("data-jpa", "lombok", "h2");
    }

    @Test
    void 고른_의존성을_선언_순서대로_넣는다() {
        String script = SpringBuildScript.render(EnumSet.of(SpringDependency.LOMBOK, SpringDependency.JPA));

        assertThat(script.indexOf("data-jpa")).isLessThan(script.indexOf("compileOnly 'org.projectlombok:lombok'"));
        assertThat(script).contains("annotationProcessor 'org.projectlombok:lombok'");
    }

    @Test
    void 한_줄짜리_옛_템플릿에도_덧붙인다() {
        String patched = SpringBuildScript.addMissing(OLD_TEMPLATE, JPA_AND_LOMBOK);

        assertThat(patched).startsWith(OLD_TEMPLATE.substring(0, OLD_TEMPLATE.indexOf("dependencies")));
        assertThat(patched).contains(
                "dependencies { implementation 'org.springframework.boot:spring-boot-starter-web'\n"
                        + "    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'\n"
                        + "    compileOnly 'org.projectlombok:lombok'\n"
                        + "    annotationProcessor 'org.projectlombok:lombok'\n"
                        + "}\n");
    }

    @Test
    void 이미_있는_줄은_다시_넣지_않는다() {
        String script = "dependencies {\n"
                + "    implementation(\"org.springframework.boot:spring-boot-starter-data-jpa\")\n"
                + "    compileOnly 'org.projectlombok:lombok:1.18.30'\n"
                + "}\n";

        String patched = SpringBuildScript.addMissing(script, JPA_AND_LOMBOK);

        assertThat(patched).isEqualTo("dependencies {\n"
                + "    implementation(\"org.springframework.boot:spring-boot-starter-data-jpa\")\n"
                + "    compileOnly 'org.projectlombok:lombok:1.18.30'\n"
                + "    annotationProcessor 'org.projectlombok:lombok'\n"
                + "}\n");
    }

    @Test
    void 다_있으면_원래_내용_그대로() {
        String script = SpringBuildScript.render(EnumSet.of(SpringDependency.JPA, SpringDependency.LOMBOK));

        assertThat(SpringBuildScript.addMissing(script, JPA_AND_LOMBOK)).isSameAs(script);
    }

    @Test
    void buildscript_안의_dependencies_에는_넣지_않는다() {
        String script = "buildscript {\n    dependencies {\n        classpath 'x:y:1'\n    }\n}\n"
                + "dependencies {\n    implementation 'a:b'\n}\n";

        String patched = SpringBuildScript.addMissing(script, List.of(SpringDependency.H2.gradleLines().get(0)));

        assertThat(patched).isEqualTo("buildscript {\n    dependencies {\n        classpath 'x:y:1'\n    }\n}\n"
                + "dependencies {\n    implementation 'a:b'\n    runtimeOnly 'com.h2database:h2'\n}\n");
    }

    @Test
    void dependencies_블록이_없으면_새로_만든다() {
        String patched = SpringBuildScript.addMissing("plugins { id 'java' }\n",
                List.of(SpringDependency.H2.gradleLines().get(0)));

        assertThat(patched).isEqualTo("plugins { id 'java' }\n\ndependencies {\n    runtimeOnly 'com.h2database:h2'\n}\n");
    }

    @Test
    void 다른_설정_이름이면_있는_것으로_치지_않는다() {
        // compileOnly 만 있고 annotationProcessor 가 없으면 Lombok 어노테이션이 코드로 안 바뀐다.
        assertThat(SpringBuildScript.contains("compileOnly 'org.projectlombok:lombok'",
                "annotationProcessor 'org.projectlombok:lombok'")).isFalse();
    }

    @Test
    void 모르는_의존성_이름은_거절한다() {
        assertThatThrownBy(() -> SpringDependency.parse(List.of("JPA", "implementation 'evil:x'")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(SpringDependency.parse(List.of("jpa", " h2 "))).containsExactly(SpringDependency.JPA, SpringDependency.H2);
        assertThat(SpringDependency.parse(null)).isEmpty();
    }

    @Test
    void DB_설정은_JPA_와_DB_를_골랐을_때만_만든다() {
        assertThat(SpringAppConfig.render(EnumSet.of(SpringDependency.H2))).isEmpty();
        assertThat(SpringAppConfig.render(EnumSet.of(SpringDependency.JPA))).isEmpty();
        assertThat(SpringAppConfig.render(EnumSet.of(SpringDependency.JPA, SpringDependency.H2)))
                .get().asString().contains("jdbc:h2:mem:").doesNotContain("console:");
        // 둘 다 고르면 바로 실행되는 H2 를 쓴다.
        assertThat(SpringAppConfig.render(EnumSet.of(SpringDependency.JPA, SpringDependency.H2, SpringDependency.MYSQL)))
                .get().asString().contains("jdbc:h2:mem:");
        assertThat(SpringAppConfig.render(EnumSet.of(SpringDependency.JPA, SpringDependency.MYSQL)))
                .get().asString().contains("jdbc:mysql://");
    }
}
