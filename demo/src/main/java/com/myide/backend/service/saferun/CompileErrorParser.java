// 경로: src/main/java/com/myide/backend/service/saferun/CompileErrorParser.java
package com.myide.backend.service.saferun;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 컴파일러 출력에서 "에러가 난 파일"을 뽑아 장부와 같은 형식의 경로로 돌려준다.
 *
 * javac 와 gradle(compileJava)은 둘 다 에러를 이렇게 적는다.
 * <pre>
 *   Main.java:5: error: ';' expected                       (javac, 실행한 폴더 기준 상대경로)
 *   /app/src/main/java/com/x/A.java:12: error: cannot ...  (gradle, 컨테이너 안 절대경로)
 * </pre>
 *
 * 경고(warning)는 빌드를 실패시키지 않으므로 세지 않는다.
 */
public final class CompileErrorParser {

    /** 컨테이너 안에서 프로젝트가 놓이는 곳. DockerService 가 /app 으로 복사한다. */
    private static final String CONTAINER_ROOT = "/app/";

    private static final Pattern ERROR_LINE =
            Pattern.compile("^\\s*(?:\\[[^\\]]*\\]\\s*)?(.+?\\.java):\\d+: error:", Pattern.MULTILINE);

    private CompileErrorParser() {
    }

    /**
     * @param output     컴파일러가 낸 출력 전체
     * @param workingDir 컴파일 명령을 실행한 폴더(브랜치 폴더 기준 상대경로, 루트면 빈 문자열)
     * @return 에러가 난 파일들의 브랜치 폴더 기준 상대경로
     */
    public static Set<String> errorFiles(String output, String workingDir) {
        Set<String> files = new LinkedHashSet<>();

        if (output == null || output.isBlank()) {
            return files;
        }

        String prefix = EditLedger.normalizePath(workingDir);
        Matcher matcher = ERROR_LINE.matcher(output.replace("\r\n", "\n"));

        while (matcher.find()) {
            String raw = matcher.group(1).trim().replace("\\", "/");
            String path;

            if (raw.startsWith(CONTAINER_ROOT)) {
                path = raw.substring(CONTAINER_ROOT.length());
            } else if (raw.startsWith("/")) {
                // 프로젝트 밖의 파일(JDK 소스 등)은 누구의 변경도 아니다.
                continue;
            } else {
                String relative = EditLedger.normalizePath(raw);
                path = prefix.isEmpty() ? relative : prefix + "/" + relative;
            }

            path = EditLedger.normalizePath(path);

            if (!path.isEmpty()) {
                files.add(path);
            }
        }

        return files;
    }
}
