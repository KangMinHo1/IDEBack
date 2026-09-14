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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성된 코드가 실제로 쓸 수 있는 모양인지 확인한다.
 *
 * 여기서 보는 것은 "파일이 나왔다"가 아니라 <b>틀리면 컴파일되지 않거나
 * 화면이 안 뜨는 지점</b>이다. 외래키 컬럼이 값 필드와 연관 필드로 두 번
 * 나오는지, 경로 변수가 react-router 문법으로 바뀌는지, 라우트 파일이
 * import 하는 이름과 화면 파일 이름이 같은지 같은 것들이다.
 */
class CodegenGeneratorsTest {

    private final DesignModelV2 model = CodegenFixtures.board();
    private final CodegenOptions options =
            new CodegenOptions("com.example.board", ProjectStack.SPRING);

    // ── Entity ──────────────────────────────────────────────────────

    @Test
    @DisplayName("외래키 컬럼은 값이 아니라 연관 필드로 한 번만 나온다")
    void foreignKeyBecomesAssociation() {
        String post = contentOf(new SpringEntityGenerator().generate(model, options),
                "Post.java");

        assertThat(post).contains("@ManyToOne(fetch = FetchType.LAZY)");
        assertThat(post).contains("@JoinColumn(name = \"author_id\", nullable = false)");
        assertThat(post).contains("private User author;");

        // 같은 컬럼이 Long author_id 로도 나오면 필드가 두 개가 된다.
        assertThat(post).doesNotContain("private Long authorId;");
    }

    @Test
    @DisplayName("기본키에는 @Id 와 자동 증가가 붙는다")
    void primaryKeyIsGenerated() {
        String user = contentOf(new SpringEntityGenerator().generate(model, options),
                "User.java");

        assertThat(user).contains("@Id");
        assertThat(user).contains("@GeneratedValue(strategy = GenerationType.IDENTITY)");
        assertThat(user).contains("private Long id;");
    }

    @Test
    @DisplayName("날짜 타입은 import 를 붙이고 짧은 이름으로 쓴다")
    void timeTypesAreImported() {
        String user = contentOf(new SpringEntityGenerator().generate(model, options),
                "User.java");

        assertThat(user).contains("import java.time.LocalDateTime;");
        assertThat(user).contains("private LocalDateTime joinedAt;");
        assertThat(user).doesNotContain("private java.time.LocalDateTime");
    }

    @Test
    @DisplayName("TEXT 컬럼은 columnDefinition 으로 알려 준다")
    void textColumnKeepsItsType() {
        String post = contentOf(new SpringEntityGenerator().generate(model, options),
                "Post.java");

        assertThat(post).contains("columnDefinition = \"TEXT\"");
    }

    @Test
    @DisplayName("엔티티 주석에 어느 요구사항에서 나왔는지 남는다")
    void entityKeepsTraceability() {
        String user = contentOf(new SpringEntityGenerator().generate(model, options),
                "User.java");

        assertThat(user).contains("R-01 로그인");
    }

    // ── Repository ──────────────────────────────────────────────────

    @Test
    @DisplayName("관계가 있으면 그 관계로 찾는 메서드가 들어간다")
    void repositoryHasDerivedQuery() {
        String repository = contentOf(new SpringRepositoryGenerator().generate(model, options),
                "PostRepository.java");

        assertThat(repository).contains("extends JpaRepository<Post, Long>");
        assertThat(repository).contains("List<Post> findByAuthor_Id(Long authorId);");
        assertThat(repository).contains("import java.util.List;");
    }

    // ── Controller / DTO ────────────────────────────────────────────

    @Test
    @DisplayName("경로 변수와 요청 본문이 각각 제자리에 붙는다")
    void controllerBindsParameters() {
        List<GeneratedFile> files = new SpringControllerDtoGenerator(new ObjectMapper())
                .generate(model, options);

        String userController = contentOf(files, "UserController.java");
        String postController = contentOf(files, "PostController.java");

        assertThat(userController).contains("@PostMapping(\"/api/users/login\")");
        assertThat(userController).contains("@RequestBody CreateUsersLoginRequest request");

        assertThat(postController).contains("@GetMapping(\"/api/posts/{postId}\")");
        // 표준 CRUD 로 인식되면 경로 변수 타입도 표의 기본키를 따른다.
        assertThat(postController).contains("@PathVariable(\"postId\") Long postId");
    }

    @Test
    @DisplayName("표준 CRUD 는 몸통까지 만들고, 그 밖은 스텁으로 둔다")
    void standardCrudGetsRealBody() {
        List<GeneratedFile> files = new SpringControllerDtoGenerator(new ObjectMapper())
                .generate(model, options);

        String postController = contentOf(files, "PostController.java");

        // GET /api/posts/{postId} 는 표준 조회다. 서비스를 부르고 몸통이 채워진다.
        assertThat(postController).contains("private final PostService postService;");
        assertThat(postController).contains("postService.findById(postId)");

        // POST /api/posts/{postId}/like 는 동작이라 기계가 무엇을 할지 알 수 없다.
        assertThat(postController).contains("UnsupportedOperationException");
    }

    @Test
    @DisplayName("표준 CRUD 가 있는 표에는 서비스가 생긴다")
    void serviceIsGeneratedForStandardCrud() {
        List<GeneratedFile> files = new SpringServiceGenerator().generate(model, options);

        String service = contentOf(files, "PostService.java");

        assertThat(service).contains("public Post findById(Long id)");
        assertThat(service).contains("postRepository.findById(id)");

        // 설계에 없는 CRUD 는 만들지 않는다. 쓰지 않는 코드가 남으면 안 된다.
        assertThat(service).doesNotContain("public void delete(");
    }

    @Test
    @DisplayName("예시 JSON 이 있으면 DTO record 를 만든다")
    void jsonExampleBecomesRecord() {
        List<GeneratedFile> files = new SpringControllerDtoGenerator(new ObjectMapper())
                .generate(model, options);

        String request = contentOf(files, "CreateUsersLoginRequest.java");

        assertThat(request).contains("public record CreateUsersLoginRequest(");
        assertThat(request).contains("String email");
        assertThat(request).contains("String password");
    }

    @Test
    @DisplayName("예시가 JSON 이 아니면 Map 으로 물러선다")
    void nonJsonExampleFallsBackToMap() {
        List<GeneratedFile> files = new SpringControllerDtoGenerator(new ObjectMapper())
                .generate(model, options);

        String postController = contentOf(files, "PostController.java");

        assertThat(postController).contains("ResponseEntity<Map<String, Object>>");
        assertThat(postController).contains("import java.util.Map;");
        assertThat(files.stream().map(GeneratedFile::path))
                .noneMatch(path -> path.endsWith("GetPostsByIdResponse.java"));
    }

    @Test
    @DisplayName("컨트롤러 주석에 요구사항과 부르는 화면이 남는다")
    void controllerKeepsTraceability() {
        String controller = contentOf(new SpringControllerDtoGenerator(new ObjectMapper())
                .generate(model, options), "UserController.java");

        assertThat(controller).contains("요구사항: R-01 로그인");
        assertThat(controller).contains("부르는 화면: 로그인 화면 (/login)");
    }

    // ── DDL ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("외래키는 CREATE TABLE 밖으로 빼서 순서 문제를 없앤다")
    void foreignKeysComeAfterTables() {
        String sql = contentOf(new DdlGenerator().generate(model, options),
                "design-schema.sql");

        int createPosts = sql.indexOf("CREATE TABLE IF NOT EXISTS `posts`");
        int alter = sql.indexOf("ALTER TABLE `posts`");

        assertThat(createPosts).isGreaterThan(-1);
        assertThat(alter).isGreaterThan(createPosts);
        assertThat(sql).contains("FOREIGN KEY (`author_id`) REFERENCES `users` (`id`)");
        assertThat(sql).contains("ON DELETE CASCADE");

        // 외래키가 CREATE TABLE 안에 남아 있으면 만드는 순서를 타게 된다.
        assertThat(sql.substring(createPosts, alter)).doesNotContain("FOREIGN KEY");
    }

    @Test
    @DisplayName("기본키 하나짜리 정수 컬럼에만 AUTO_INCREMENT 를 붙인다")
    void autoIncrementOnlyForSinglePk() {
        String sql = contentOf(new DdlGenerator().generate(model, options),
                "design-schema.sql");

        assertThat(sql).contains("`id` BIGINT NOT NULL AUTO_INCREMENT");
        assertThat(sql).contains("`title` VARCHAR(200) NOT NULL");
        assertThat(sql).contains("PRIMARY KEY (`id`)");
    }

    // ── React ───────────────────────────────────────────────────────

    @Test
    @DisplayName("화면 경로가 그대로 라우트가 되고 시작 화면이 표시된다")
    void routesFollowScreenKeys() {
        String routes = contentOf(new ReactRouteGenerator().generate(model, options),
                "AppRoutes.jsx");

        assertThat(routes).contains("<Route path=\"/login\" element={<LoginPage />} />");
        assertThat(routes).contains("<Route path=\"/posts/:postId\"");
        assertThat(routes).contains("시작 화면");
    }

    @Test
    @DisplayName("라우트가 import 하는 이름과 화면 파일 이름이 같다")
    void routeImportsMatchPageFiles() {
        String routes = contentOf(new ReactRouteGenerator().generate(model, options),
                "AppRoutes.jsx");
        List<GeneratedFile> pages = new ReactPageStubGenerator().generate(model, options);

        for (GeneratedFile page : pages) {
            String component = page.path()
                    .substring(page.path().lastIndexOf('/') + 1)
                    .replace(".jsx", "");

            assertThat(routes)
                    .as("라우트 파일이 %s 를 import 해야 한다", component)
                    .contains("import " + component + " from \"../pages/" + component + "\";");
        }
    }

    @Test
    @DisplayName("화면 뼈대에 이동 버튼과 부르는 API 가 그려진다")
    void pageShowsFlow() {
        String login = contentOf(new ReactPageStubGenerator().generate(model, options),
                "LoginPage.jsx");

        assertThat(login).contains("import { useNavigate } from \"react-router-dom\";");
        assertThat(login).contains("navigate(\"/posts/:postId\")");
        assertThat(login).contains("로그인 버튼 클릭");
        assertThat(login).contains("POST /api/users/login");
    }

    @Test
    @DisplayName("이동할 곳이 없는 화면은 useNavigate 를 가져오지 않는다")
    void pageWithoutTransitionsHasNoNavigate() {
        String detail = contentOf(new ReactPageStubGenerator().generate(model, options),
                "PostsDetailPage.jsx");

        assertThat(detail).doesNotContain("useNavigate");
    }

    @Test
    @DisplayName("API 호출 함수는 경로 변수를 인자로 받는다")
    void apiClientTakesPathParams() {
        String client = contentOf(new ReactApiClientGenerator().generate(model, options),
                "designApi.js");

        assertThat(client).contains("export function createUsersLogin(body) {");
        assertThat(client).contains("request(\"POST\", `/api/users/login`, body)");
        assertThat(client).contains("export function getPostsById(postId) {");
        assertThat(client).contains("request(\"GET\", `/api/posts/${postId}`)");
    }

    // ── 멱등성 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 설계로 두 번 만들면 글자 하나까지 같다")
    void generationIsDeterministic() {
        List<DesignCodeGenerator> generators = List.of(
                new SpringEntityGenerator(), new SpringRepositoryGenerator(),
                new SpringControllerDtoGenerator(new ObjectMapper()), new DdlGenerator(),
                new ReactRouteGenerator(), new ReactPageStubGenerator(),
                new ReactApiClientGenerator());

        for (DesignCodeGenerator generator : generators) {
            List<GeneratedFile> first = generator.generate(model, options);
            List<GeneratedFile> second = generator.generate(CodegenFixtures.board(), options);

            assertThat(second)
                    .as("%s 는 같은 입력에 같은 결과를 내야 한다", generator.getClass().getSimpleName())
                    .isEqualTo(first);
        }
    }

    private String contentOf(List<GeneratedFile> files, String fileName) {
        return files.stream()
                .filter(file -> file.path().endsWith("/" + fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        fileName + " 이 생성되지 않았습니다. 나온 것: "
                                + files.stream().map(GeneratedFile::path).toList()))
                .content();
    }
}
