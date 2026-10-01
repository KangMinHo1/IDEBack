// 경로: src/main/java/com/myide/backend/service/saferun/RunCommands.java
package com.myide.backend.service.saferun;

import com.myide.backend.domain.LanguageType;
import com.myide.backend.domain.workspace.TemplateType;

/**
 * 안전 실행용 셸 명령을 만든다.
 *
 * 기존 실행은 "컴파일 && 실행"을 한 줄로 넘겨서 컴파일이 실패했는지를 서버가 알 수 없다.
 * 여기서는 둘을 나눠, 컨테이너 안에서 컴파일을 먼저 따로 돌리고(종료 코드로 성패를 안다)
 * 성공했을 때만 실행을 돌린다.
 */
public final class RunCommands {

    /**
     * @param workingDir      명령을 실행할 폴더(브랜치 폴더 기준 상대경로, 루트면 빈 문자열)
     * @param compile         컴파일 명령
     * @param run             컴파일이 성공했을 때 이어서 실행할 명령
     * @param cleanClassFiles 컴파일 전에 예전 .class 를 지울지
     */
    public record Commands(String workingDir, String compile, String run, boolean cleanClassFiles) {

        /** 컨테이너에서 돌릴 컴파일 명령. 출력은 표준 출력 하나로 모은다(에러 순서가 섞이지 않게). */
        public String compileShell() {
            // 컨테이너를 재사용하므로 앞선 시도가 만든 .class 가 남아 있을 수 있다. 이번 조합에서
            // 빠진 파일의 .class 가 남아 있으면, 그것을 참조하는 코드가 컴파일을 통과해 버린다.
            String clean = cleanClassFiles ? "find . -name '*.class' -delete; " : "";

            return changeDirectory() + "{ " + clean + compile + " ; } 2>&1";
        }

        /** 컨테이너에서 돌릴 실행 명령. */
        public String runShell() {
            return changeDirectory() + run;
        }

        private String changeDirectory() {
            return workingDir.isEmpty() ? "" : "cd " + quote(workingDir) + " || exit 1; ";
        }
    }

    private RunCommands() {
    }

    /** 안전 실행을 적용하는 유형인가. 컴파일 단계가 분명한 Java 콘솔과 Spring Boot 뿐이다. */
    public static boolean supports(TemplateType templateType, LanguageType language) {
        return templateType == TemplateType.SPRING_BOOT
                || (templateType == TemplateType.CONSOLE && language == LanguageType.JAVA);
    }

    public static Commands forTemplate(TemplateType templateType, LanguageType language, String filePath) {
        if (templateType == TemplateType.SPRING_BOOT) {
            // gradle 은 지워진 소스의 산출물을 스스로 정리하므로 .class 를 따로 지우지 않는다.
            return new Commands("", "gradle compileJava", TemplateType.SPRING_BOOT.getRunCommand(), false);
        }

        return forJavaConsole(language, filePath);
    }

    /**
     * Java 콘솔. 기존 실행(DockerService.runProject)이 만드는 명령과 같은 것을 "&&" 에서 둘로 나눈다.
     * 명령이 달라지면 안전 실행에서만 되거나 안 되는 프로그램이 생기므로 같은 규칙을 따른다.
     */
    private static Commands forJavaConsole(LanguageType language, String filePath) {
        String normalized = EditLedger.normalizePath(filePath);
        String workingDir = "";
        String fileName = normalized;

        if (normalized.contains("/")) {
            workingDir = normalized.substring(0, normalized.lastIndexOf('/'));
            fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        }

        String command = language.getRunCommand()
                .replace("{file}", quote(fileName))
                .replace("src/", "")
                .replace("-cp src", "-cp .")
                .replace("--project src", "--project .");

        int split = command.indexOf(" && ");

        return new Commands(
                workingDir,
                command.substring(0, split),
                command.substring(split + " && ".length()),
                true
        );
    }

    /** 셸에 그대로 넘겨도 안전하게 작은따옴표로 감싼다. 경로는 클라이언트가 보낸 값이다. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
