// 경로: src/main/java/com/myide/backend/service/saferun/SyntaxChecker.java
package com.myide.backend.service.saferun;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 파일 하나가 문법적으로 완성돼 있는지 본다(괄호를 안 닫았는지 등).
 *
 * 컴파일이 아니다. 타입이 맞는지, 없는 메서드를 불렀는지는 보지 않는다. 그 대신
 * Docker 도 빌드도 없이 서버 안에서 밀리초 만에 끝나므로, 팀원이 "쓰는 중"인지
 * "다 썼는지"를 실행 없이 가늠하는 데 쓴다. 진짜 판정은 실행할 때의 컴파일이 한다.
 */
@Component
public class SyntaxChecker {

    /**
     * @return 문법 오류가 없으면 true. Java 가 아닌 파일은 검사하지 않고 통과시킨다.
     */
    public boolean isValid(String filePath, String code) {
        if (filePath == null || !filePath.toLowerCase(Locale.ROOT).endsWith(".java")) {
            return true;
        }

        // JavaParser 인스턴스는 스레드 안전하지 않아 매번 새로 만든다.
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        return new JavaParser(configuration)
                .parse(code == null ? "" : code)
                .isSuccessful();
    }
}
