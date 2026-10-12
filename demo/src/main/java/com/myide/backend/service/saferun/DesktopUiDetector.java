// 경로: src/main/java/com/myide/backend/service/saferun/DesktopUiDetector.java
package com.myide.backend.service.saferun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * 실행할 코드가 창(Swing·AWT)을 띄우는지 겉만 보고 가린다.
 *
 * 화면 중계는 창을 쓰는 프로젝트에서만 켠다. 항상 켜면 API 만 있는 프로젝트의 미리보기에도
 * 빈 검은 화면이 뜬다. 따로 설정을 두지 않고 코드를 보는 이유는, 설정은 코드와 어긋나기
 * 때문이다(창 코드는 지웠는데 설정은 켜져 있는 식).
 */
public final class DesktopUiDetector {

    /** 빌드 산출물과 도구 폴더. 소스가 아니라서 보지 않는다. */
    private static final Set<String> SKIPPED_DIRS = Set.of("build", ".gradle", ".git", "node_modules");

    private DesktopUiDetector() {
    }

    public static boolean usesDesktopUi(Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return false;
        }

        boolean[] found = {false};

        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();

                    return name != null && SKIPPED_DIRS.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java") && mentionsDesktopUi(file)) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return false;
        }

        return found[0];
    }

    private static boolean mentionsDesktopUi(Path file) {
        try {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            return source.contains("javax.swing") || source.contains("java.awt");
        } catch (IOException e) {
            return false;
        }
    }
}
