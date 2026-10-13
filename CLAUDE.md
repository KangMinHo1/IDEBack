# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

웹 기반 협업 IDE의 백엔드(Spring Boot 3.3.2 / Java 17, 소스 321개). 사용자 코드를 Docker 컨테이너에서 실행·디버깅하고, Git worktree로 브랜치를 분리하며, WebSocket으로 실시간 협업(동시편집/터미널/실행 출력/채팅/음성/파일트리 이벤트)을 제공한다. 여기에 **설계 문서 → 검사 → 코드 생성 → 최종 보고서**로 이어지는 파이프라인이 얹혀 있다.

**실제 Gradle 프로젝트는 저장소 루트가 아니라 `demo/` 하위에 있다.** 모든 빌드/실행 명령은 `demo/`에서 실행한다. 루트 `README.md`는 사실상 비어 있으므로 참고할 것이 없다.

기능과 무관한 잔여물: 루트 `hs_err_pid*.log`(JVM 크래시 로그), `demo/쇼핑몰 프로젝트 0906-*`(비어 있는 워크스페이스 찌꺼기). 루트 `uploads/`는 게시판 첨부파일 저장 위치이며 `WebConfig`가 `/uploads/**`로 서빙한다.

## 명령어

모두 `demo/` 디렉터리 기준:

```bash
./gradlew bootRun          # 서버 실행 (8080)
./gradlew build            # 빌드 + 테스트
./gradlew test             # 전체 테스트
./gradlew test --tests "com.myide.backend.service.design.doctor.DesignDoctorServiceTest"   # 단일 클래스
./gradlew test --tests "*Codegen*"                                                          # 패턴 매칭
./gradlew clean build -x test
```

코드 실행 환경 이미지는 서버 기동 전에 **직접 빌드**해 두어야 한다(없으면 실행/터미널/디버그가 전부 실패):

```bash
docker build -t ide-execution-env .    # demo/Dockerfile — Ubuntu 22.04 + JDK17/Gradle/Python3+debugpy/Node18/.NET8/gdb
```

Swagger UI: `http://localhost:8080/swagger-ui.html` (springdoc 기본 설정, 별도 config 클래스 없음).

## 필수 설정

`demo/src/main/resources/application-secret.yml` (gitignore 대상, `application.yml`이 optional import). 아래 키가 없으면 AI 기능이 예외를 던진다:

```yaml
google:
  gemini:
    api-key: ...
    url: ...
```

환경변수로 덮어쓰는 값: `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`(기본 MySQL `localhost:3306/myide`, `ddl-auto: update`), `JWT_SECRET`, `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` / `GITHUB_REDIRECT_URI`.

`application.yml`의 `DOCKER_HOST_URL` / `IDE_WORKSPACE_PATH`는 **아무도 읽지 않는 죽은 설정이다.** `DockerConfig`는 docker-java의 `createDefaultConfigBuilder()`로 호스트 환경(표준 `DOCKER_HOST` 등)을 자동 감지하고, 워크스페이스 경로는 `WorkspaceService`가 자기 상수로 정한다.

## 아키텍처 핵심

### 워크스페이스 = 물리 디렉터리 + worktree 레이아웃

DB 엔티티와 파일시스템이 1:1로 묶여 있다. 경로 규칙은 **`WorkspaceService`가 단일 진실 공급원**이며, 경로를 직접 조립하지 말고 항상 `getProjectPath(workspaceId, projectName, branchName)`를 쓴다.

```
<workspace.path>/<projectName>/<branchFolderName>/
```

- 워크스페이스 루트 기본값 `WorkspaceService.DEFAULT_ROOT = C:\WebIDE\workspaces` (하드코딩, Windows 전용 경로).
- 기본 브랜치는 `master`. 레거시 이름 `main-repo`는 `normalizeBranchName()`이 `master`로 통일한다.
- `master` 폴더가 실제 git 저장소이고, 다른 브랜치는 `git worktree add`로 만든 별도 폴더다(`ProjectService.createBranch` → `GitService.createWorktree`).
- 브랜치명의 `/`가 디렉터리로 쪼개지지 않도록 `toBranchFolderName()`이 URL 인코딩한다(`feature/login` → `feature%2Flogin`). **폴더명과 브랜치명을 혼동하지 말 것.**

`FileService`는 모든 경로를 `root.resolve(...).normalize()` 후 `startsWith(root)`로 검사하고, 쓰기 후 `codeMapService.invalidateCache(...)`를 호출한다. **파일을 직접 `Files.write`로 쓰지 말고 `FileService`를 거칠 것** — 경로 탈출 방어와 코드맵 캐시 무효화가 거기에만 있다(`DesignCodegenService`도 이 규칙을 따른다).

### 실행/디버그/터미널: Docker 컨테이너 + WebSocket

`DockerService`(docker-java)가 `ide-execution-env` 이미지로 컨테이너를 띄우고 워크스페이스 경로를 마운트/복사해 명령을 실행한다. 컨테이너는 **WebSocket 세션 ID를 키로** `ConcurrentHashMap`에 추적된다(`runningContainerMap`, `terminalContainerMap`, 입력용 `PipedOutputStream` 맵 2개). 서버 부팅 시 `@PostConstruct cleanUpZombieContainers()`가 남은 컨테이너를 강제 삭제한다.

실행 명령은 두 enum이 조합해 만든다:
- `TemplateType`(domain/workspace): 유형별 포트 + 실행 명령 — `SPRING_BOOT` 8080/`gradle bootRun`, `REACT` 5173, `NEXT` 3000, `VANILLA` 8000, `CONSOLE` 0(LanguageType 명령을 따름).
- `LanguageType`: 언어별 run/build 명령 템플릿(`{file}`, `{output}` 치환), 기본 파일명, boilerplate — JAVA/PYTHON/JAVASCRIPT/TYPESCRIPT/CPP/C/CSHARP/HTML.

새 언어·템플릿을 추가하려면 이 enum들과 대응 전략 구현체를 함께 손봐야 한다.

### WebSocket 이중 구조

두 방식이 공존하므로 새 기능 추가 시 어느 쪽인지 먼저 확인할 것.

**Raw 핸들러** (`WebSocketConfig`, `handler/` 패키지):

| 엔드포인트 | 핸들러 | 형태 |
|---|---|---|
| `/ws/run`, `/ws/debug`, `/ws/terminal` | Run/Debug/TerminalWebSocketHandler | 텍스트 JSON(`{"type":"RUN"/"STOP"/"INPUT"...}`)을 `ObjectMapper.readTree`로 수동 파싱 |
| `/ws/collab` | CollaborationWebSocketHandler | **바이너리**(Yjs). 유일하게 `CollabHandshakeInterceptor`로 JWT + 멤버십을 검사 |
| `/ws/workspace-events` | WorkspaceEventWebSocketHandler | 파일 트리 변경 브로드캐스트 |

**STOMP** (`StompWebSocketConfig`): 엔드포인트 `/ws/chat`, `/ws/webrtc`, `/ws/presence`. 브로커 `/topic`, `/queue`, 앱 prefix `/app`. `WebSocketAuthChannelInterceptor`가 CONNECT 프레임의 `Authorization` 헤더에서 JWT를 검증하고 `WebSocketSessionAuthRegistry`에 sessionId→userId를 보관한다.

CORS 허용 오리진 패턴은 `SecurityConfig`와 `WebSocketConfig`에 **각각 중복 정의**되어 있다(localhost + 192.168/10/172.16~31 사설 대역). 한쪽만 고치면 증상이 반쪽만 사라진다.

### 동시편집(`/ws/collab`) — 서버는 중계기일 뿐

서버는 Yjs 바이너리를 해석하지 않는다. 방(room)마다 세션 집합과 base64 문서 사본만 들고 있고, **방이 비면 문서도 사라진다.** 그래서 최초 내용은 클라이언트끼리 주고받아 채운다(먼저 도착한 것만 채택하고 나머지에게는 채택본을 회신).

방 이름 규약은 `security/CollabRoomName` 한곳에 있다. 접속 검사(`CollabHandshakeInterceptor`)와 시드(`CollabRoomController`)가 같은 해석을 써야 하므로 여기를 고칠 때는 양쪽을 함께 본다.

```
design:{workspaceId}                        설계 문서
{workspaceId}:{project}:{branch}:{file}     코드 에디터 (isFileRoom — 디스크 내용을 시드)
global-workspace-room-{workspaceId}         워크스페이스 전역 동기화
```

메시지 크기 한도는 `WebSocketConfig.MAX_MESSAGE_BYTES`(1MB)이며, **컨테이너 설정과 세션별 설정 양쪽을 모두 올려야** 한다(`ServletServerContainerFactoryBean` + `afterConnectionEstablished`의 `setBinary/TextMessageSizeLimit`). 한쪽만 올리면 큰 변경이 여전히 1009로 끊긴다. 이 설정 때문에 `DemoApplicationTests`는 `WebEnvironment.RANDOM_PORT`로 실제 서블릿 컨테이너를 띄운다.

### 안전 실행 · 샌드박스 (`service/saferun`, `service/sandbox`)

프론트 연동 명세와 옛 API 제거 목록은 `docs/safe-run-sandbox.md` 에 있다. 여기서는 고칠 때 깨면 안 되는 것만 적는다.

- **서버는 여전히 Yjs 를 해석하지 않는다.** 편집 장부(`EditLedger`)는 메시지 앞 두 바이트(`YjsMessageKind`)와 핸드셰이크의 회원번호만 보고 "누가 어느 파일을 고쳤는지"를 적는다. 문법 검사(`SyntaxChecker`)는 파일이 **저장될 때**(`IdeController.saveFile`) 돈다 — 서버가 내용을 볼 수 있는 곳이 디스크뿐이기 때문이다.
- **live 폴더에는 쓰지 않는다.** 실행은 `{ide.safe-run-root}/runs/{runId}` 에 조립한 사본으로 한다(`RunAssembler`). 조립 규칙과 다음 빌드 결정(`RunPlanner`)은 순수 로직이라 단위 테스트가 촘촘하다. 규칙을 바꾸면 `RunAssemblerTest` · `RunPlannerTest` 를 먼저 본다.
- **실행한 본인이 고친 파일은 어떤 조합에서도 live 그대로다.** 다른 작성자를 제외해도 되돌리지 않는다(본인 변경이 몰래 빠지면 안 된다).
- 기존 실행(`DockerService.runProject`)은 그대로 두고 안전 실행용 메서드(`openWorkbench` · `copyInto` · `execAndWait` · `execStreaming` · `closeWorkbench`)를 따로 두었다. RUN 메시지에 유효한 `token` 이 있고 Java 콘솔·Spring Boot 일 때만 안전 실행으로 가고, 아니면 기존 경로다.
- **안전 실행 한 번은 컨테이너 하나를 재사용한다.** `sleep infinity` 로 띄워 두고, 시도마다 달라진 파일만 바꿔 넣고(`RunDelta`) `docker exec` 로 컴파일한다(종료 코드로 성패를 안다). 성공하면 같은 컨테이너에서 실행한다. 컨테이너는 `RunService.run` 의 `finally` 에서 반드시 정리한다.
- Spring Boot 안전 실행은 서버 전체에서 동시에 `ide.safe-run.spring-boot-concurrency`(기본 1)개만 돈다(`RunQueue`). 자리는 앱이 내려갈 때까지 쥔다.
- 샌드박스는 브랜치가 아니라 `{safe-run-root}/{ws}/sandbox/…/{userId}/{files,base,meta.json}` 이다. 방이 열려 있는 파일을 서버가 디스크에 직접 쓰면 안 된다(`runIfRoomClosed`) — 방의 문서가 옛 내용이라 자동 저장이 덮는다.
- `FileService.saveFile` 은 협업 자동 저장(`allowEmpty == false`)이 **없는 파일을 새로 만들지 못하게** 막는다. 지운 파일이 되살아나던 버그의 방어다.
- 옛 샌드박스(`GitController.createSandbox` / `applySandbox`)는 `@Deprecated` 이고 호출마다 경고 로그를 남긴다. 프론트 전환이 끝나면 지운다.
- 진짜 Docker 로 도는 통합 테스트: `SAFE_RUN_DOCKER_IT=1 ./gradlew test --tests "*RunServiceDockerIT"` (평소에는 건너뛴다).

### 인증과 권한

JWT(jjwt) access(15분) + refresh(14일, `RefreshToken` 엔티티 + `refreshToken` 쿠키). `JwtAuthenticationFilter`가 SecurityContext를 채우지만, 서비스 계층은 대부분 `CurrentUserService.getCurrentUserId()`로 **요청 헤더에서 토큰을 다시 파싱**해 userId를 얻는다 — 기존 코드를 따를 것.

`SecurityConfig`는 개발 편의를 위해 `anyRequest().permitAll()`이고 `/api/github/**`만 `authenticated()`다. **즉 대부분의 권한 검사는 서비스 계층 책임이다.**

워크스페이스 접근 검사는 `security/WorkspaceAccessGuard.requireAccess(workspaceId, userId)`가 단일 창구다(소유자 우대, 초대 미수락 `PENDING` 멤버는 거부). 새 워크스페이스 스코프 기능은 자체 검사 메서드를 복제하지 말고 이걸 주입해 쓴다. 다만 `DesignDocumentService`·`DevlogService` 등 오래된 서비스는 아직 각자 `getAccessibleWorkspace(...)`를 갖고 있으니, 그 파일을 수정할 때는 어느 쪽인지 확인한다.

### 설계(design) 파이프라인

가장 크고 규칙이 많은 영역. 편집기는 Yjs로 돌지만 **저장본이 유일한 보관처**다(`DesignSnapshotService`).

```
클라이언트(Yjs) ──스냅샷+평문사본──> DesignSnapshotService ──> DesignDocSnapshot (+ 역투영 저장)
                                          │
              DesignModelCodec (DesignModelV2 <-> JSON, 모르는 필드는 무시)
                                          │
        ┌──────────────┬──────────────────┼──────────────────────┐
   DesignDraftService  DesignDoctorService  DesignCodegenService  FinalReportService
   (AI 초안)           (규칙 검사)          (코드 생성/적용)       (최종 보고서)
```

- **평문 사본(projection)**: 서버는 Yjs를 못 읽으므로 클라이언트가 스냅샷과 함께 보내는 평문 JSON을 `DesignModelCodec`으로 읽는다. 이 코덱이 v2 모델의 유일한 입출구다.
- **역투영(`LegacyProjectionV2`)**: 저장할 때마다 예전 형식(`/design/requirements`, `/design/apis`, `/design/document`)으로도 되쓴다. 자료실·마이페이지가 그 옛 엔드포인트를 그대로 호출하기 때문 — 되쓰기를 빼면 그 화면들이 조용히 빈다.
- **체크포인트**: 워크스페이스당 최대 20개(`MAX_CHECKPOINTS`), 새 스냅샷이 이전의 50% 미만으로 줄면(`SHRINK_CHECKPOINT_RATIO`) 덮어쓰기 전에 자동으로 하나 남긴다.
- **설계 닥터(`service/design/doctor`)**: `DesignRule` 구현체(`ApiRules`/`ErdRules`/`RequirementRules`/`ScreenRules`/`TraceRules`)를 List 주입으로 모아 돌린다. DB를 읽지 않고 요청 본문의 문서만 본다(저장 안 된 편집 중 상태도 검사해야 하므로). 검사는 **서버에만** 둔다 — 코드 생성이 이 결과로 막히고 보고서도 같은 결과를 쓴다.
- **기계적 보정(`DesignRepairs`)**: AI 초안 후처리와 닥터의 "고치기" 버튼이 같은 규칙을 공유하고, 예약어 목록은 `ErdRules.RESERVED`를 그대로 쓴다. 판정하는 쪽과 고치는 쪽이 어긋나면 버튼을 눌러도 오류가 안 사라지므로 한쪽만 고치지 말 것.
- **코드 생성(`service/design/codegen`)**: `DesignCodeGenerator` 구현체들이 Spring(엔티티/리포지토리/컨트롤러DTO/DDL)과 React(라우트/페이지 스텁/API 클라이언트)를 만든다. 두 가지 불변식 — ① 닥터에 ERROR가 있으면 아무것도 만들지 않는다, ② 파일은 반드시 `FileService`를 통해 쓴다. 대상 패키지·스택은 사용자 입력이 아니라 `ProjectStackDetector`가 디스크를 보고 판단한다(패키지를 잘못 짚으면 생성된 자바 파일이 하나도 컴파일되지 않으므로).

주요 엔드포인트: `/api/workspaces/{id}/design/doc`(+`/seed`, `/snapshot`, `/checkpoints`), `/design/doctor`, `/design/codegen/{targets|preview|apply}`, `/design/ai/{skeleton|detail}`.

### AI 호출 경로 (셋으로 갈라져 있음)

| 클라이언트 | 쓰는 곳 | 특징 |
|---|---|---|
| `CoreAiService.generateText` | `AiAssistService`, `CodeMapService`, `RearrangeService` | RestTemplate, 재시도·옵션 없음, 응답 첫 조각만 읽음 |
| `service/ai/GeminiHttpClient` | `DesignDraftService` | 재시도 3회 + 생성 옵션, 조각난 큰 JSON 응답 병합 |
| `service/aireport/GeminiReportClient` | `FinalReportService` | 보고서 전용, 자체 재시도 |

출력이 크거나 JSON을 강제하는 새 기능은 `GeminiHttpClient` 쪽을 쓴다. AI에 JSON 형식을 지시하는 프롬프트가 많으므로 응답 파싱을 바꿀 때 프롬프트의 포맷 지시도 함께 확인할 것.

### 전략 패턴 4종 (Spring이 List로 주입 → `supports()`로 선택)

| 인터페이스 | 위치 | 용도 |
|---|---|---|
| `ProjectTemplateStrategy` | `service/template` | 프로젝트 스캐폴딩 (Console/SpringBoot/React/Next/Vanilla) |
| `DebugStrategy` | `service/debug` | 언어별 디버깅 (Java/Python/JS/C++/HTML) |
| `CodeAnalyzer` | `service/analyzer` | 코드맵 분석 (Java/SpringBoot/Python/React) |
| `DesignRule` / `DesignCodeGenerator` | `service/design/...` | 설계 검사 규칙 / 코드 생성기 |

구현체에 `@Component`/`@Service`만 붙이면 자동 등록되고, 분기 로직 추가는 필요 없다.

### 그 밖의 도메인

`/api` 아래 컨트롤러 30여 개. 위 IDE 핵심 외에는 대체로 평범한 CRUD다.

- **협업 부가기능**: 채팅(STOMP), WebRTC 음성(`VoiceChannelService` + `VoiceRoomRegistry`, 세션 종료는 `WebRtcDisconnectEventListener`가 처리), 접속 현황(`WorkspacePresenceRegistry` + `WorkspacePresenceDisconnectListener`).
- **API 테스트기**(`controller/apitest`, `service/apitest`): 프록시로 외부 요청을 대신 보낸다. `SsrfGuard`가 대상 URL을 검사하지만 **`ALLOW_PRIVATE_NETWORK_FOR_DEMO = true`라 사설/루프백 대역이 열려 있다** — 배포 전에 꺼야 하는 시연용 설정.
- **GitHub 연동**: `GithubOAuthController`(콜백) + `GithubController` + `GithubCommitService`. JGit 의존성이 있지만 실제 저장소 조작은 대부분 `GitService`가 `git` CLI를 `ProcessBuilder`로 호출한다(worktree, merge 등). 토큰은 push/pull/fetch 시 인자로 전달된다.
- **게시판/마이페이지/개발일지/일정/알림**: `PostService`(QueryDSL), `MyPageActivityService`, `DevlogService`(일정과 연결), `ScheduleService`, `NotificationService`.
- **가상 파일트리 재배치**(`RearrangeService`): AI로 "보기용" 트리를 만들어 워크스페이스+브랜치+뷰 이름으로 저장한다. 실제 파일은 건드리지 않는다.
- **코드맵**(`CodeMapService`): `CodeAnalyzer` 분석 결과를 `CodeMapCache`/`CodeSummary`에 캐시한다. 파일 쓰기 시 `FileService`가 무효화한다.

## 테스트

테스트는 설계 파이프라인에 집중되어 있다(codegen, doctor, codec 계약, AI 초안, 체크포인트, 협업 방 문서, 핸드셰이크 인증). `src/test/resources/design/{projection,expected-legacy}.json`이 코덱/역투영 계약의 골든 파일이므로, 역투영 형식을 바꾸면 이 파일도 함께 고쳐야 한다. 대부분 스프링 컨텍스트 없이 도는 순수 단위 테스트이며, `DemoApplicationTests`만 실제 포트로 컨텍스트를 띄운다.

## 알아둘 함정

- **예외 핸들러가 둘**: `exception/GlobalExceptionHandler`(문자열 body)와 `handler/ApiGlobalExceptionHandler`(Map body). `IllegalArgumentException`과 `Exception`을 둘 다 처리하므로 응답 형태가 상황에 따라 달라진다. 새 핸들러를 추가하기 전에 양쪽을 확인할 것.
- **`@Scheduled`가 동작하지 않는다**: `NotificationScheduleJob`에 `@Scheduled(cron = "0 0 9 * * *")`가 있지만 어디에도 `@EnableScheduling`이 없다.
- **`application.yml`에 죽은 값이 섞여 있다**: `spring.ide.docker.*`(`host`, `workspace-path`)는 어느 코드도 읽지 않고, `GITHUB_REDIRECT_URI` 기본값 끝에는 `%7D`(`}`)가 잘못 붙어 있다.
- QueryDSL Q타입은 annotationProcessor로 생성된다. `PostRepositoryImpl`만 커스텀 QueryDSL 구현을 쓴다.
- 코드베이스의 주석·로그·에러 메시지가 한국어이며 이모지를 자주 쓴다. 최근 코드일수록 "왜 이렇게 했는가"를 길게 적은 Javadoc이 붙어 있으니, 그런 파일을 고칠 때는 주석의 근거를 먼저 읽고 스타일을 따를 것.
