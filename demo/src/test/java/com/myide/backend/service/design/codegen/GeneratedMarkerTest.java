package com.myide.backend.service.design.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.service.design.codegen.react.ReactApiClientGenerator;
import com.myide.backend.service.design.codegen.react.ReactPageStubGenerator;
import com.myide.backend.service.design.codegen.react.ReactRouteGenerator;
import com.myide.backend.service.design.codegen.spring.DdlGenerator;
import com.myide.backend.service.design.codegen.spring.SpringControllerDtoGenerator;
import com.myide.backend.service.design.codegen.spring.SpringEntityGenerator;
import com.myide.backend.service.design.codegen.spring.SpringRepositoryGenerator;
import com.myide.backend.service.design.codegen.spring.SpringServiceGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드맵이 "설계 관리에서 만든 파일"을 알아보는지 확인한다.
 *
 * 판별 근거는 생성기 템플릿에 들어 있는 헤더 주석 문구뿐이다. 누가 템플릿
 * 문구를 바꾸면 에러 없이 코드맵의 AI 표시만 조용히 사라진다. 그래서 여기서
 * <b>모든 생성기가 만든 모든 파일</b>을 판별기에 통과시켜, 문구와 판별기가
 * 어긋나는 순간 테스트가 깨지게 한다.
 */
class GeneratedMarkerTest {

    private final DesignModelV2 model = CodegenFixtures.board();
    private final CodegenOptions options =
            new CodegenOptions("com.example.board", ProjectStack.SPRING);

    private List<GeneratedFile> everyGeneratedFile() {
        List<GeneratedFile> files = new ArrayList<>();

        files.addAll(new SpringEntityGenerator().generate(model, options));
        files.addAll(new SpringRepositoryGenerator().generate(model, options));
        files.addAll(new SpringServiceGenerator().generate(model, options));
        files.addAll(new SpringControllerDtoGenerator(new ObjectMapper()).generate(model, options));
        files.addAll(new DdlGenerator().generate(model, options));
        files.addAll(new ReactRouteGenerator().generate(model, options));
        files.addAll(new ReactPageStubGenerator().generate(model, options));
        files.addAll(new ReactApiClientGenerator().generate(model, options));

        return files;
    }

    @Test
    @DisplayName("모든 생성기가 만든 모든 파일을 생성 파일로 알아본다")
    void everyGeneratedFileIsRecognized() {
        List<GeneratedFile> files = everyGeneratedFile();

        // 생성기가 아무것도 안 만들면 아래 검사가 공짜로 통과하므로 먼저 막는다.
        assertThat(files).isNotEmpty();

        List<String> missed = files.stream()
                .filter(file -> !GeneratedMarker.isGenerated(GeneratedMarker.head(file.content())))
                .map(GeneratedFile::path)
                .toList();

        assertThat(missed)
                .as("생성 표식을 못 찾은 파일. 그 생성기 템플릿에 \"%s\" 문구를 넣을 것",
                        GeneratedMarker.PHRASE)
                .isEmpty();
    }

    @Test
    @DisplayName("사람이 쓴 평범한 코드는 생성 파일로 보지 않는다")
    void handWrittenCodeIsNotRecognized() {
        String java = """
                package com.example.board.service;

                /**
                 * 게시글 조회수를 올린다.
                 */
                public class ViewCounter {
                }
                """;
        String jsx = """
                // 로그인 화면
                export default function Login() {
                  return <div>로그인</div>;
                }
                """;

        assertThat(GeneratedMarker.isGenerated(java)).isFalse();
        assertThat(GeneratedMarker.isGenerated(jsx)).isFalse();
    }

    @Test
    @DisplayName("디스크의 생성 파일을 읽어 알아보고, 없는 파일은 false 로 넘긴다")
    void readsFromDisk(@TempDir Path dir) throws Exception {
        GeneratedFile entity = new SpringEntityGenerator().generate(model, options).get(0);
        Path file = dir.resolve("Post.java");
        Files.writeString(file, entity.content(), StandardCharsets.UTF_8);

        assertThat(GeneratedMarker.readsAsGenerated(file)).isTrue();
        assertThat(GeneratedMarker.readsAsGenerated(dir.resolve("missing.java"))).isFalse();
    }
}
