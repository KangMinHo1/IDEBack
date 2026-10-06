# 안전 실행 · 샌드박스 — 프론트 연동 명세

백엔드에 **안전 실행**과 **새 샌드박스(개인 레이어)** 가 들어갔다. 이 문서는 프론트 담당자가 이것만 보고 화면을 옮길 수 있도록 쓴 것이다.

- 백엔드는 이미 배포해도 된다. **프론트를 고치기 전에는 화면 동작이 지금과 같다**(토큰 없는 실행은 예전 방식 그대로, 옛 샌드박스 API 도 그대로 동작).
- 옛 샌드박스 API 는 호출될 때마다 서버에 `[Deprecated]` 경고 로그가 남는다. 이 로그가 더 안 찍히면 전환이 끝난 것이다.

목차: [1. 무엇이 달라지나](#1-무엇이-달라지나) · [2. 공통 규칙](#2-공통-규칙) · [3. 안전 실행](#3-안전-실행) · [4. 작성자 상태](#4-작성자-상태-상태바) · [5. 샌드박스 API](#5-샌드박스-api) · [6. 샌드박스에서 편집하기](#6-샌드박스에서-편집하기) · [7. 반영 절차](#7-반영-절차) · [8. 기존 API → 새 API](#8-기존-api--새-api-대응표) · [9. 전환 순서](#9-프론트-전환-순서) · [10. 알려진 한계](#10-알려진-한계) · [11. 서버 설정](#11-서버-설정) · [12. 전환 후 제거 목록](#12-전환이-끝난-뒤-제거할-것)

---

## 1. 무엇이 달라지나

| | 지금 | 바뀐 뒤 |
| --- | --- | --- |
| 실행 | 팀원이 쓰다 만 코드까지 그대로 컴파일 → 에러 | 서버가 "돌아가는 조합"을 찾아 실행. 누구 변경을 뺐는지 알려 줌 |
| 상태바 | 없음 | `정상 버전: 2분 전 \| 민수 준비됨 \| 지영 수정 중` |
| 샌드박스 | 개인 브랜치(`focus-u{회원번호}-…`)를 만들고 전환·병합 | 브랜치는 그대로. 켜면 **내가 고친 파일만** 팀원에게 안 보인다. 안 고친 파일은 계속 실시간 공유 |
| 반영 | 브랜치 병합(커밋 메시지 필요, 충돌 시 Git 화면) | 파일마다 3-way 병합. 충돌 없는 파일은 바로 들어가고 충돌 파일만 비교 화면 |

용어:

- **live** — 팀이 함께 고치는 브랜치 폴더(지금의 그 폴더).
- **green(정상 버전)** — 마지막으로 컴파일에 성공한 사본. 서버가 따로 보관한다.
- **편집 장부** — "green 이후 어떤 파일을 누가 고쳤는지". 서버 메모리에 있다.

**안전 실행은 Java 콘솔과 Spring Boot 에만 적용된다.** React·Next·Python·C/C++ 등은 지금처럼 live 폴더로 바로 실행된다. 디버그도 지금 그대로다.

---

## 2. 공통 규칙

- 인증: REST 는 지금과 같은 `Authorization: Bearer {accessToken}`. 새 API 는 전부 워크스페이스 멤버만 쓸 수 있다(아니면 401/403).
- 브랜치 위치는 항상 `workspaceId` · `projectName` · `branchName` 세 값으로 준다(`branchName` 을 비우면 `master`).
- `filePath` 는 지금과 같은 **브랜치 폴더 기준 상대경로**(`src/Main.java`).
- 회원번호(`userId`)는 숫자다. 누구의 샌드박스인지는 요청 본문이 아니라 **토큰에서** 읽는다.

---

## 3. 안전 실행

### 3-1. RUN 메시지에 추가할 필드 (`/ws/run`)

```jsonc
{
  "type": "RUN",
  "workspaceId": "…", "projectName": "…", "branchName": "develop",
  "filePath": "src/Main.java", "language": "JAVA", "templateType": "CONSOLE",

  "token": "{accessToken}",        // 있어야 안전 실행. 없으면 예전 방식 그대로 실행된다
  "safeRunInfo": true,             // [SAFE_RUN_INFO] 줄을 받겠다 (3-3)
  "sandbox": false,                // 샌드박스를 켠 채 실행하면 true
  "includeAuthors": [],            // (선택) 사용자가 직접 고른 포함 작성자의 회원번호
  "excludeAuthors": []             // (선택) 사용자가 직접 고른 제외 작성자의 회원번호
}
```

- `token` 이 유효하고, 그 워크스페이스의 멤버이고, 유형이 Java 콘솔 또는 Spring Boot 일 때만 안전 실행이 된다. 하나라도 아니면 **조용히 예전 방식으로** 실행된다.
- `includeAuthors` 를 비우면 서버가 "준비됨(READY)인 사람 전원"으로 시작한다.
- **실행한 본인은 무엇을 보내도 제외되지 않는다.**
- `STOP` · `INPUT` 은 지금과 같다.

### 3-2. 실행 직전 저장 (반드시 지킬 것)

서버가 보는 live 는 **디스크**이고, 디스크는 협업 자동 저장 때문에 최대 30초 늦다. 그래서 수정 직후 실행하면 방금 고친 내용이 빠질 수 있다.

> **RUN 을 보내기 전에, 지금 보고 있는 파일을 저장하고(팀 공유 방이면 `POST /api/workspaces/save`, `sbx` 방이면 `PUT /api/sandbox/file`) 응답을 받은 뒤 RUN 을 보낸다.**

- 내용은 Redux 가 아니라 **화면(문서)에서 직접** 읽는다. Redux 는 타이핑 0.4초 뒤에야 갱신된다.
- **다른 탭은 저장하지 않는다.** 에디터는 화면에 하나뿐이고, 동시편집 세션도 지금 보고 있는 파일 하나에만 붙어 있다. 다른 탭에 남은 것은 그 탭을 떠날 때의 Redux 사본이라, 그것을 저장하면 그 사이 팀원이 고친 내용을 옛 사본으로 덮는다. 그 탭들은 떠날 때 이미 저장됐거나(내가 저장 담당이었던 경우), 그 파일을 열어 둔 사람이 저장한다.
- 그래서 **내가 열어 두지 않은 파일의 최근 변경은 최대 30초 늦을 수 있다.** 이 틈은 프론트에서 메울 수 없다(10절).
- 토큰(`token`)은 **팀 모드에서만** 싣는다. 서버는 "누가 고쳤는지"를 동시편집 소켓으로만 아는데 개인 모드는 그 소켓을 쓰지 않는다. 개인 모드에서 실으면 내 수정이 "출처 모르는 변경"으로 취급돼, 컴파일이 깨졌을 때 내 수정이 빠진 옛 정상 버전이 실행된다.

### 3-3. 출력

실행 출력은 지금처럼 텍스트로 온다. 달라지는 점:

1. 사람이 읽는 안내 줄이 프로그램 출력 **앞에** 온다. 그대로 출력 창에 찍으면 된다.
   ```
   [System] 지영 님이 수정 중인 변경(Helper.java)을 빼고 실행했어요
   [System] 마지막 정상 버전에 내 변경만 더해 실행했어요
   [System] 컴파일에 실패했어요
   [System] Helper.java 은(는) 지영 님과 함께 수정 중인 파일이에요
   [System] 다른 실행이 끝나기를 기다리는 중
   ```
   마지막 줄은 Spring Boot 에서만 나온다. 서버 전체에서 Spring Boot 안전 실행은 동시에 정해진 개수(기본 1개)만 돌고, 나머지는 **앞사람의 앱이 내려갈 때까지** 기다린다. 기다리는 동안 중지(`STOP`)를 누르거나 소켓을 닫으면 차례에서 빠진다. 이 줄이 온 뒤 한동안 아무 출력이 없는 것은 정상이므로, 실행 버튼을 "대기 중" 상태로 보여 주면 좋다.
2. `safeRunInfo: true` 를 보냈으면 기계용 줄이 **메시지 하나에 통째로** 온다. **출력 창에 찍지 말고 가로채서** 쓴다(`[SERVER_STARTED_PORT:…]` 를 가로채는 것과 같은 방식).
   ```
   [SAFE_RUN_INFO]{"success":true,"builds":2,"mode":"EXCLUDED","excluded":[…],"sharedFiles":[],"errorFiles":[],"greenUpdated":false}
   ```
   `safeRunInfo` 를 보내지 않으면 이 줄은 오지 않는다.
3. 실패해서 버린 빌드의 컴파일 에러는 오지 않는다. 마지막 빌드의 출력만 온다.
4. 끝날 때는 지금처럼 `--- Execution Finished ---` 뒤에 소켓이 닫힌다. Spring Boot 는 지금처럼 `[SERVER_STARTED_PORT:{port}]` 가 온다(컴파일이 성공한 뒤에).

`[SAFE_RUN_INFO]` 의 내용:

| 필드 | 뜻 |
| --- | --- |
| `success` | 컴파일에 성공해 실행했는가 |
| `builds` | 빌드를 몇 번 했는가(1~3) |
| `mode` | 마지막 빌드의 조합. `FULL`(빌드 1: 준비된 전원 + 본인) · `EXCLUDED`(빌드 2: 에러 낸 작성자 제외) · `OWN_ONLY`(빌드 3: 정상 버전 + 내 변경만) · `NO_GREEN`(정상 버전이 아직 없어 live 그대로) |
| `excluded` | `[{userId, nickname, files}]` — 변경이 빠진 작성자와, 실제로 정상 버전으로 되돌린 파일 |
| `sharedFiles` | `[{file, coAuthors:[{userId, nickname}]}]` — 에러가 난 파일 중 **나와 다른 사람이 함께 고친 파일**. 내 변경이 몰래 빠지면 안 되므로 서버가 되돌리지 않았고, 그래서 에러가 남았다 |
| `errorFiles` | 실패했을 때 에러가 난 파일 경로 |
| `greenUpdated` | 이번 실행으로 정상 버전이 갱신됐는가 |

서버가 빌드를 고르는 순서(참고):

```
빌드 1  준비된 작성자 전원 + 본인
  실패 → 에러 파일의 작성자를 장부에서 찾는다
     · 전부 본인이 고친 파일(함께 고친 파일 포함) → 에러를 보여 주고 끝
     · 다른 사람이 고친 파일                      → 빌드 2
     · 누가 고쳤는지 모르는 파일뿐                → 빌드 3
빌드 2  찾은 작성자를 제외 (본인은 제외하지 않음)
빌드 3  정상 버전 + 내 변경만
```

---

## 4. 작성자 상태 (상태바)

### 4-1. 처음 한 번 조회

`GET /api/safe-run/status?workspaceId=…&projectName=…&branchName=…`

```json
{
  "workspaceId": "…", "projectName": "shop", "branchName": "develop",
  "greenUpdatedAt": 1790000000000,
  "authors": [
    { "userId": 7,  "nickname": "민수", "status": "READY",   "files": ["src/Order.java"] },
    { "userId": 12, "nickname": "지영", "status": "EDITING", "files": ["src/Main.java"] }
  ]
}
```

- `greenUpdatedAt` — 정상 버전을 만든 시각(epoch 밀리초). 아직 없으면 `null`. "2분 전"은 프론트가 계산한다.
- `authors` — 정상 버전 이후 무언가 고친 사람만 나온다. 아무도 안 고쳤으면 빈 배열.
- `status` — 그 사람이 고친 파일이 전부 문법 검사를 통과하면 `READY`, 하나라도 실패하면 `EDITING`. Java 가 아닌 파일은 검사하지 않고 통과로 본다.

화면을 열 때와 **브랜치·프로젝트를 바꿀 때마다** 다시 조회한다.

### 4-2. 실시간 알림

새 소켓은 없다. 이미 붙어 있는 `/ws/workspace-events`(방: `workspace:{id}:project:{name}:branch:{branch}`)로 온다. `type` 으로 구분한다.

| `type` | 언제 | 본문 |
| --- | --- | --- |
| `AUTHOR_STATUS_CHANGED` | 누군가의 상태가 바뀌거나 작성자가 생기고 사라질 때. **바뀔 때만** 온다 | 4-1 응답과 같은 필드 + `type` |
| `GREEN_UPDATED` | 정상 버전이 갱신됐을 때 | 4-1 응답과 같은 필드 + `type` |
| `SANDBOX_TEAM_FILE_CHANGED` | 팀 파일이 저장·삭제·이름 변경됐고, **그 파일을 자기 샌드박스에 둔 사람이 한 명이라도 있을 때** | `{type, workspaceId, projectName, branchName, filePath}`. 누구의 샌드박스인지는 싣지 않는다 — 받은 쪽이 자기 목록에 있는 파일(또는 그 폴더 아래 파일)일 때만 `GET /api/sandbox/state` 를 다시 부른다(6-5) |

두 알림 모두 "전체 상태"를 싣고 오므로 받은 것으로 통째로 갈아 끼우면 된다. `Sidebar.jsx` 의 이벤트 핸들러에 `FILE_TREE_CHANGED` · `BRANCH_CHANGED` 와 나란히 분기를 추가한다.

검사 시점: 서버는 **파일이 저장될 때** 문법을 본다(협업 자동 저장은 입력이 3초 멈추면 나간다). 그래서 상태는 타이핑 후 3초쯤 뒤에 바뀐다.

---

## 5. 샌드박스 API

전부 `/api/sandbox`. 요청 본문은 같은 모양이고 쓰지 않는 필드는 비운다.

```jsonc
{ "workspaceId": "…", "projectName": "…", "branchName": "…",
  "filePath": "src/Main.java",   // 파일을 다루는 API 만
  "liveContent": "…",            // fork, merge, resolve 만
  "code": "…" }                  // PUT /file, resolve 만
```

| 메서드·경로 | 하는 일 | 응답 |
| --- | --- | --- |
| `POST /enable` | 샌드박스를 켠다. **깃발만 바꾼다** — 브랜치를 만들지도 바꾸지도 않는다 | `State` |
| `POST /disable` | 끈다. **고친 파일은 지우지 않고 보관**한다. 다시 켜면 이어서 작업 | `State` |
| `GET /state?workspaceId&projectName&branchName` | 켜짐 여부와 **반영 안 된 파일 목록** | `State` |
| `POST /files/fork` | 파일을 처음 고치는 순간 부른다. `liveContent` 로 사본을 뜬다(6절) | `{filePath, content}` |
| `GET /file?workspaceId&projectName&branchName&path` | 샌드박스에서 본 파일 내용(내가 고쳤으면 내 것, 아니면 팀 파일) | `{filePath, content}` |
| `PUT /file` | 샌드박스 파일 저장(`code`) | 204 |
| `DELETE /file` | 샌드박스에서 파일 삭제. 팀 파일은 그대로 | 204 |
| `POST /merge` | 반영하면 어떻게 되는지 **판정만** 한다. 아무것도 쓰지 않는다 | `MergeView` |
| `POST /pull` | 팀 변경을 내 샌드박스 파일로 가져온다(`code` = 내 문서의 지금 내용, `liveContent` = 팀 문서의 지금 내용). 팀 파일은 안 바뀐다(6-5) | `MergeView` |
| `POST /resolve` | 충돌을 해결한 결과(`code`)를 저장하고 비교 기준을 `liveContent` 로 옮긴다(7-3) | 204 |
| `POST /applied` | 브라우저가 팀 문서에 넣은 뒤 부른다. 그 파일을 샌드박스에서 뺀다 | 204 |
| `POST /apply-direct` | 서버가 팀 파일에 직접 반영한다(7절) | `MergeView` 또는 409 |
| `POST /discard` | 버린다. 내 샌드박스 수정이 **모두** 사라진다 | 204 |

```jsonc
// State
{ "enabled": true,
  "files": [ { "filePath": "src/Main.java", "kind": "MODIFIED", "teamChanged": true },   // 팀 파일을 고침
             { "filePath": "src/New.java",  "kind": "NEW",      "teamChanged": false },  // 새로 만듦
             { "filePath": "src/Old.java",  "kind": "DELETED",  "teamChanged": false } ] } // 지움

// MergeView
{ "filePath": "src/Main.java", "status": "CLEAN", "content": "…", "roomOpen": true }
```

`teamChanged` — 내가 그 파일을 고치기 시작한 뒤로 **팀 파일이 달라졌는지**(디스크 기준). 샌드박스에서 고치는 파일은 팀원의 편집이 화면에 보이지 않으므로, 이 값이 "팀이 이 파일을 고쳤다"를 알 수 있는 유일한 단서다(6-5절).

`MergeView.status`:

| 값 | 뜻 | `content` |
| --- | --- | --- |
| `UNCHANGED` | 반영할 것이 없다(내가 안 바꿨거나, 팀 파일이 이미 같은 내용) | 팀 파일의 지금 내용 |
| `CLEAN` | 충돌 없이 합쳐진다 | **팀 파일에 넣을 병합 결과** |
| `CONFLICT` | 같은 곳을 팀도 고쳤다 | 충돌 표식(`<<<<<<< sandbox` / `=======` / `>>>>>>> team`)이 든 본문. 위쪽이 내 것 |
| `NEW_FILE` | 팀 쪽에 없는 파일. 반영하면 새로 생긴다 | 내 파일 내용 |
| `DELETED` | 내가 지운 파일. 반영하면 팀 쪽에서도 지워진다 | `null` |
| `DELETE_CONFLICT` | 내가 지운 파일을 그 사이 팀이 고쳤다 | 팀 파일의 지금 내용 |

`roomOpen` — 그 파일의 **팀 공유 방**이 열려 있는지(누가 접속해 있거나 서버에 문서가 보관돼 있는지). 반영 방법이 이것으로 갈린다(7절).

`POST /apply-direct` 의 409:

```json
{ "code": "ROOM_OPEN", "filePath": "src/Main.java", "message": "팀원이 열어 둔 파일입니다. 브라우저에서 반영해야 합니다: src/Main.java" }
```

오류가 아니라 "이 파일은 브라우저 쪽 반영(7-1)을 쓰라"는 뜻이다.

---

## 6. 샌드박스에서 편집하기

### 6-1. 방 이름

샌드박스에서 고치는 파일은 팀 공유 방이 아니라 **개인 방**에서 편집한다.

```
팀 공유 방      {workspaceId}:{project}:{branch}:{file}              (지금 그대로)
개인 샌드박스 방  sbx:{userId}:{workspaceId}:{project}:{branch}:{file}
```

- `sbx` 방은 **주인만** 들어갈 수 있다. 방 이름의 `userId` 와 토큰의 회원번호가 다르면 접속(`/ws/collab`)도, 문서 REST(`/api/collab/rooms/doc*`)도 403 이다. 같은 워크스페이스 멤버여도 마찬가지다.
- 접속 방법은 팀 공유 방과 같다(`CodeDocSession.open()` 의 순서 그대로: GET doc → 없으면 seed → WebSocket). **시드에 쓸 내용은 디스크가 아니라 `GET /api/sandbox/file` 로 받은 내용**이다.
- `sbx` 방의 편집은 서버의 편집 장부에 적히지 않는다(팀 파일을 고친 것이 아니므로).

### 6-2. 저장 경로 — 가장 조심할 곳

> **`sbx` 방의 저장은 `saveFile`(`/api/workspaces/save`)이 아니라 `PUT /api/sandbox/file` 로 간다.**

`sbx` 방에서 평소처럼 `/api/workspaces/save` 를 부르면 **팀 파일을 내 샌드박스 내용으로 덮는다.** `CodeDocSession` 을 만들 때 넘기는 `saveFile` 콜백을 방 종류에 따라 갈아 끼운다. 실행 전 저장과 "모두 저장"도 같은 규칙을 따른다.

### 6-3. 아직 안 고친 파일을 고치기 시작할 때

샌드박스를 켜도 **고치기 전의 파일은 팀 공유 방에 그대로 붙어 있는다**(그래야 팀원의 편집이 계속 보인다). 다만 그 상태에서 입력을 받으면 팀원에게 보이므로 **읽기 전용**으로 둔다.

```
[안 고친 파일]   팀 공유 방 + 읽기 전용.  표시: "👥 팀 실시간 보기 중"  버튼: [샌드박스에서 고치기]
       │  버튼을 누르거나, 그냥 입력하려 하거나(Monaco onDidAttemptReadOnlyEdit)
       ▼
1. POST /files/fork      liveContent = 팀 공유 문서(Y.Text)의 지금 내용
2. 방 전환               팀 공유 방 세션을 닫고 sbx 방을 연다 (응답의 content 로 시드)
       ▼
[고친 파일]      sbx 방 + 편집 가능.  표시: "📝 샌드박스에서 수정 중"
```

- **1번의 `liveContent` 는 반드시 보낸다.** 서버의 디스크는 최대 30초 늦다. 보내지 않으면 팀원이 방금 친 내용이 내 샌드박스에서 사라지고, 반영할 때 그 내용을 "내가 지운 것"으로 착각한다.
- **눌렀던 글자는 다시 넣지 않는다.** 버튼 없이 바로 입력하려다 전환된 경우 "샌드박스로 전환했어요. 이어서 입력하세요"만 짧게 띄운다. 한글 조합·붙여넣기·자동 들여쓰기·자동완성까지 되살리는 것은 깨지기 쉬워서 하지 않는다.
- 전환하는 동안(fork 요청부터 sbx 방 문서가 붙을 때까지)은 읽기 전용을 유지한다. 실패하면 팀 방 읽기 전용 그대로 두고 오류를 알린다.
- 샌드박스 상태(`GET /state`)를 아직 받아 오지 못한 동안에도 입력을 막는다. 켜져 있었다면 그 입력이 팀 방으로 들어가기 때문이다.
- 이미 고친 파일(`State.files` 에 있는 파일)은 열 때부터 sbx 방으로 연다.
- 2번에서 팀 공유 방 세션을 닫을 때는 지금의 `destroy()` 규칙(끊기 전에 저장 여부 판정)을 그대로 지킨다.
- `fork` 는 여러 번 불러도 안전하다. 이미 사본이 있으면 그것을 돌려준다.

### 6-4. 그 밖

- 샌드박스에서 **새 파일**을 만들 때: `PUT /api/sandbox/file` 로 바로 저장하면 된다(`kind: "NEW"`). `/api/workspaces/files` 는 부르지 않는다 — 부르면 팀 폴더에 생긴다.
- 샌드박스에서 **삭제**: `DELETE /api/sandbox/file`.
- 탐색기: 팀 파일 트리(`fetchProjectFilesApi`)에 `State.files` 를 덧씌워 그린다(`NEW` 는 추가, `DELETED` 는 숨김, `MODIFIED` 는 표시).
- 파일을 열 때: `State.files` 에 있으면 `sbx` 방, 없으면 팀 공유 방.
- 샌드박스를 **끌 때**: 열어 둔 `sbx` 방 탭을 저장하고 닫는다. 파일은 서버에 남아 있고, 다시 켜면 `GET /state` 로 목록을 되찾는다.
- 브랜치를 바꾸면 샌드박스도 그 브랜치 것으로 바뀐다(샌드박스는 워크스페이스·프로젝트·브랜치·사람마다 따로다). 브랜치를 바꿀 때 `GET /state` 를 다시 부른다.

### 6-5. 고치는 동안 팀이 그 파일을 고쳤을 때 — 알림과 "팀 변경 가져오기"

파일을 샌드박스에서 고치기 시작하면 그 파일은 개인 방으로 옮겨지므로, **그때부터 팀원의 편집이 화면에 보이지 않는다.** 팀원의 수정이 없어지는 것은 아니다 — 반영할 때 3-way 로 합쳐지거나 충돌로 뜬다(7절). 다만 알려 주지 않으면 모르는 채 오래 작업하다가 반영 순간에 충돌을 한꺼번에 만난다.

```
민수(샌드박스)가 Order.java 를 고치는 중
지영(팀)이 Order.java 를 고치고 3초쯤 멈춘다        ← 팀 방의 자동 저장이 디스크에 쓴다
  서버 → 브랜치 방: SANDBOX_TEAM_FILE_CHANGED {filePath}
민수의 브라우저: 내 목록에 있는 파일이면 GET /state → files[].teamChanged = true
  에디터 위에 "👥 팀이 이 파일을 고쳤어요" + [팀 변경 가져오기]
민수가 버튼을 누른다
  1. 내 개인 방의 저장을 끝까지 기다린다
  2. liveContent = 팀 파일의 지금 내용 (팀 방이 열려 있으면 팀 문서에서, 아니면 디스크)
  3. POST /pull { filePath, code: 내 문서의 지금 내용, liveContent }
       UNCHANGED  가져올 것이 없다
       CLEAN      content = 합친 결과. 내 문서에 **바뀐 부분만** 넣는다(커서 보존)
       CONFLICT   content = 충돌 본문. 서버는 아무것도 쓰지 않았다.
                  내 문서에 넣고 7-3 의 충돌 화면으로 → 마칠 때 /resolve(liveContent 는 2번의 값)
```

- **가져오기는 팀 → 나 한 방향이다.** 팀 파일은 바뀌지 않고, 내 수정은 여전히 팀원에게 보이지 않는다.
- **자동으로 가져오지 않는다.** 팀원이 쓰다 만 코드가 내 샌드박스에 저절로 들어오면 샌드박스를 켠 이유가 없어진다.
- 가져오면 서버가 비교 기준을 그 팀 내용으로 옮긴다. 그래서 같은 변경을 또 "팀이 고쳤다"로 보지 않고, 이어서 반영하면 그 자리에서 다시 충돌하지 않는다.
- 가져오기를 한 번도 하지 않고 바로 반영해도 된다. 그때는 반영 순간에 합쳐지거나 충돌이 뜬다.
- 2번에서 팀 방이 열려 있는지는 `POST /merge`(`liveContent` 없이) 응답의 `roomOpen` 으로 안다.
- 알림은 **디스크에 저장된 뒤에** 온다. 팀원이 타이핑하는 즉시가 아니라 3초쯤 멈춘 뒤다.
- 방금 팀 문서에서 직접 읽어 가져온 내용이 아직 디스크에 없으면, 그 직후의 `GET /state` 는 잠깐 `teamChanged: true` 로 답할 수 있다. 팀 쪽 저장이 끝나면 알림이 와서 맞춰진다. 프론트는 가져온 직후에 표시를 직접 내린다.
- 가져오는 동안은 에디터를 읽기 전용으로 둔다. 합친 결과와 그 사이 친 글자가 엇갈리지 않게 하기 위함이다.

---

## 7. 반영 절차

`GET /state` 의 `files` 를 하나씩 처리한다. 파일마다 먼저 `POST /merge`(`liveContent` 없이)로 종류와 `roomOpen` 을 확인한 뒤 갈린다.

```
status = NEW_FILE 또는 DELETED            → 7-2 (서버가 처리)
status = UNCHANGED                        → POST /applied (샌드박스에서 빼기만)
status = CONFLICT 또는 DELETE_CONFLICT    → 7-3 (비교 화면)
status = CLEAN
    roomOpen = false                      → 7-2 (서버가 처리)
    roomOpen = true                       → 7-1 (브라우저가 처리)
```

### 7-1. 방이 열려 있는 파일 — 브라우저가 팀 문서에 넣는다

서버는 동시 편집 문서를 해석하지 못한다. 그래서 팀원이 열어 둔 파일은 **반영하는 사람의 브라우저**가 문서에 직접 넣는다.

```
1. 그 파일의 팀 공유 방에 접속한다       (화면에 띄우지 않는 CodeDocSession 이면 된다)
2. before = yText.toString()
3. POST /merge  { filePath, liveContent: before }
     CONFLICT        → 세션을 닫고 7-3
     UNCHANGED       → POST /applied, 끝
     CLEAN           → 4 로
4. yText.toString() 이 여전히 before 인지 확인한다. 달라졌으면 2 로 돌아간다
5. before 와 병합 결과(content)의 차이(diff)만, 하나의 트랜잭션으로 삽입/삭제한다
6. 저장을 한 번 밀어내고(flush) POST /applied
```

**5번에서 문서 전체를 지우고 다시 넣지 않는다.** 전체를 교체하면 반영 순간 팀원이 다른 위치에 친 글자가 지워지고 팀원의 커서가 맨 앞으로 튄다. 바뀐 부분만 넣으면 CRDT 가 나머지를 그대로 지킨다.

```js
// 예시 — 앞뒤로 같은 부분을 건너뛰고 가운데만 바꾼다.
// 더 잘게 나누고 싶으면 fast-diff 같은 라이브러리로 조각마다 insert/delete 한다.
function applyMerged(doc, yText, before, merged) {
  let start = 0;
  const max = Math.min(before.length, merged.length);
  while (start < max && before[start] === merged[start]) start++;

  let endBefore = before.length;
  let endMerged = merged.length;
  while (endBefore > start && endMerged > start && before[endBefore - 1] === merged[endMerged - 1]) {
    endBefore--; endMerged--;
  }

  doc.transact(() => {
    if (endBefore > start) yText.delete(start, endBefore - start);
    if (endMerged > start) yText.insert(start, merged.slice(start, endMerged));
  }, "sandbox-apply");
}
```

- 4번 확인과 5번 적용 사이에 `await` 를 두지 않는다. 같은 동기 구간이어야 그 사이에 팀원 편집이 끼어들지 않는다.
- 트랜잭션의 origin(`"sandbox-apply"`)은 `Y.UndoManager` 의 `trackedOrigins` 에 넣지 않는다. 넣으면 Ctrl+Z 한 번에 반영 전체가 되돌아간다.
- 이 편집은 평범한 동시 편집으로 팀원에게 퍼지고, 서버의 편집 장부에도 내 변경으로 적힌다.

### 7-2. 서버가 처리하는 파일 — `POST /apply-direct`

방이 닫힌 파일, 새 파일, 삭제는 서버가 팀 폴더에 직접 반영하고 파일 트리 이벤트를 보낸다.

| 경우 | 서버가 하는 일 | 브랜치 방에 가는 이벤트 |
| --- | --- | --- |
| `CLEAN` (방 닫힘) | 디스크에 병합 결과 저장 | `FILE_TREE_CHANGED`, `action: "UPDATE"` |
| `NEW_FILE` | 파일 생성 | `FILE_TREE_CHANGED`, `action: "CREATE"` |
| `DELETED` | 파일 삭제 + 그 방의 보관 문서 폐기 | `FILE_TREE_CHANGED`, `action: "DELETE"` |
| `UNCHANGED` | 샌드박스에서 빼기만 함 | 없음 |
| `CONFLICT` / `DELETE_CONFLICT` | **아무것도 안 함.** 응답으로 판정만 돌려준다 | 없음 |

- 방이 열려 있는데 `CLEAN` 을 `apply-direct` 로 보내면 409 `ROOM_OPEN` 이 온다 → 7-1 로 처리한다. `merge` 로 확인한 뒤에도 그 사이 누가 파일을 열 수 있으므로 **이 409 는 항상 처리해야 한다.**
- 새 파일과 삭제는 방이 열려 있어도 서버가 한다(문서 편집으로는 할 수 없는 일이다).
- `action: "UPDATE"` 는 새 값이다. `Sidebar.jsx` 는 지금도 모르는 action 이면 트리와 열린 파일을 다시 읽으므로 따로 손댈 것은 없다.
- 삭제된 파일을 열어 둔 팀원은 지금처럼 `DELETE` 이벤트로 탭이 닫힌다. 그때 나가는 마지막 자동 저장이 파일을 되살리던 문제는 **서버가 막았다**(10절).

### 7-3. 충돌

`CONFLICT` 의 `content` 는 git 표준 충돌 표식이 든 본문이다. 지금의 병합 충돌 화면(`CodeEditor.jsx` 의 `parseMergeConflicts` → "내 것 / 상대 것 / 둘 다")이 그대로 읽을 수 있다. 표식 위쪽(`<<<<<<< sandbox`)이 내 것, 아래쪽(`>>>>>>> team`)이 팀 것이다.

흐름:

```
1. content 를 내 sbx 방 문서에 넣어 충돌 화면을 띄운다 (팀 문서에 넣지 않는다)
2. 사용자가 고른 결과를 POST /api/sandbox/resolve 로 보낸다
     { filePath, code: 고른 결과, liveContent: 충돌을 계산할 때 /merge 에 보냈던 팀 내용 }
3. 그 파일을 다시 7절 처음부터 반영한다
```

**2번을 `PUT /file` 로 대신하면 안 된다.** 서버는 "샌드박스에서 고치기 시작한 순간의 팀 파일"을 기준으로 비교한다. 그 기준이 그대로면, 고른 결과를 다시 반영할 때 같은 자리에서 또 충돌이 난다. `/resolve` 는 기준을 `liveContent`(충돌을 본 그 팀 내용)로 옮겨, "그 내용 위에 내가 이렇게 고쳤다"로 바꿔 준다. 그 사이 팀이 또 고치지 않았다면 `CLEAN` 으로 들어가고, 또 고쳤다면 새 기준으로 다시 3-way 병합한다(다시 충돌이면 1번부터).

`liveContent` 는 방이 닫힌 파일(서버가 디스크로 판정한 경우)에도 필요하다. 그때는 `GET /api/workspaces/{id}/file` 로 팀 파일을 읽어 보낸다.

`DELETE_CONFLICT` 는 "내가 지운 파일을 팀이 고쳤다"는 뜻이다. 사용자에게 고르게 한다 — 삭제를 취소하려면 `POST /files/fork` 를 다시 부르면 삭제 표시가 풀리고, 그래도 지우려면 팀 파일 삭제 API(`DELETE /api/workspaces/files`)를 부른 뒤 `POST /applied`.

### 7-4. 버리기

`POST /discard` 한 번. 열어 둔 `sbx` 방 탭은 **저장하지 말고** 닫는다(닫으면서 저장하면 방금 버린 파일이 다시 생긴다).

---

## 8. 기존 API → 새 API 대응표

| 하려는 일 | 기존 | 새 방식 |
| --- | --- | --- |
| 샌드박스 시작 | `POST /api/git/sandbox/create` `{nickname, taskName, baseBranch}` → 브랜치 이름. 이어서 `switchBranch(샌드박스)` | `POST /api/sandbox/enable`. **브랜치 전환 없음, 작업명·기준 브랜치 입력 없음, `closeAllFiles` 없음** |
| 샌드박스인지 판정 | 브랜치 이름이 `focus-` / `focus/` 로 시작 | `GET /api/sandbox/state` 의 `enabled` |
| 내 샌드박스 목록 | 브랜치 목록에서 `focus-u{내 번호}-` 로 거름 | 없음. 샌드박스는 브랜치당 사람당 하나다 |
| 샌드박스 안에서 저장 | `saveFileApi(…, 샌드박스 브랜치, …)` | `PUT /api/sandbox/file` |
| 샌드박스 안에서 실행 | 샌드박스 브랜치로 RUN | 같은 브랜치로 RUN + `sandbox: true` |
| 반영 | Redux 의 모든 파일 저장 → `POST /api/git/sandbox/apply` `{sandboxBranch, targetBranch, commitMessage, nickname}` | 파일마다 `POST /api/sandbox/merge` → `apply-direct` 또는 (브라우저 반영 →) `applied`. **커밋 메시지·대상 브랜치 입력 없음** |
| 반영 뒤 화면 | `closeAllFiles` → `setActiveBranch(대상)` → 트리 갱신 | 브랜치는 그대로. `sbx` 방 탭을 팀 공유 방으로 다시 연다 |
| 충돌 | 409 → 대상 브랜치로 이동 → Git 화면 Status → 충돌 파일 → 커밋 → 샌드박스 브랜치 삭제(`wevaisPendingSandboxCleanup`) | `status: "CONFLICT"` → 그 자리에서 비교 화면 → 다시 반영. Git 화면·커밋·브랜치 삭제 없음 |
| 그만두기 | 브랜치 삭제(`DELETE /api/git/branches`) | `POST /api/sandbox/discard`. 잠깐 끄기만 하려면 `POST /api/sandbox/disable` |
| 반영 완료 이벤트 | `FILE_TREE_CHANGED` `action: "SANDBOX_APPLIED"` + `revision` | `FILE_TREE_CHANGED` `action: "UPDATE"/"CREATE"/"DELETE"`(서버 반영) 또는 평범한 동시 편집(브라우저 반영) |

달라지는 개념 하나: **새 반영은 커밋을 만들지 않는다.** 팀 파일을 고칠 뿐이다. 커밋은 지금처럼 Git 화면에서 따로 한다.

---

## 9. 프론트 전환 순서

앞 단계가 뒤 단계의 전제다. 각 단계는 따로 배포해도 화면이 깨지지 않는다.

1. **실행에 토큰 싣기** — RUN 에 `token`, `safeRunInfo: true` 추가. `[SAFE_RUN_INFO]` 줄을 가로채 출력 창에 안 찍히게 한다. 이것만으로 Java 콘솔·Spring Boot 가 안전 실행으로 돈다.
2. **실행 직전 저장** — 지금 보고 있는 파일을 방 종류에 맞는 경로로 저장하고 응답 뒤 RUN(3-2).
3. **상태바** — `GET /api/safe-run/status` + `AUTHOR_STATUS_CHANGED` / `GREEN_UPDATED`. 실행 버튼 옆에 포함/제외 작성자를 고르는 UI 를 붙이려면 `includeAuthors` / `excludeAuthors`.
4. **샌드박스 켜기·끄기·상태** — `enable` / `disable` / `state`. 켜졌을 때의 어두운 테마는 `activeBranch` 이름이 아니라 `State.enabled` 로 판정한다.
5. **샌드박스 편집** — `sbx` 방, 첫 입력 처리 순서(6-3), 저장 경로 갈아 끼우기(6-2), 탐색기 덧씌우기.
6. **샌드박스 실행** — RUN 에 `sandbox: true`.
7. **반영·버리기** — 7절. 브라우저 반영(7-1)과 409 `ROOM_OPEN` 처리를 빠뜨리지 말 것.
8. **옛 UI 제거** — "샌드박스 만들기" 모달(작업명·기준 브랜치), "병합" 모달(커밋 메시지·대상 브랜치), 충돌 후 Git 화면 이동, `focus-` 브랜치 숨김 처리. 서버 로그에 `[Deprecated]` 가 더 안 찍히는지 확인한다.

검증은 브라우저 창 두 개(가능하면 다른 계정)로 한다. 특히 확인할 것:

- A 가 샌드박스에서 고친 내용이 B 화면에 **안** 보이는가. A 가 안 고친 파일은 B 의 편집이 A 에게 계속 보이는가.
- B 가 파일을 열어 둔 상태에서 A 가 반영했을 때, B 화면에 바로 나타나고 **B 가 그 순간 다른 줄에 치던 글자와 커서가 그대로인가.**
- B 가 괄호를 안 닫은 채 3초 멈췄을 때 상태바가 "수정 중"이 되고, A 가 실행하면 B 의 변경을 빼고 도는가.

---

## 10. 알려진 한계

- **디스크는 최대 30초 늦다.** 서버가 보는 팀 파일은 디스크다. 실행 직전 저장(3-2)과 `liveContent`(fork, merge)가 이 틈을 메운다. 팀원이 "저장 전"인 내용까지 실행에 넣을 방법은 없다.
- **장부는 서버 메모리에만 있다.** 서버를 재시작하면 작성자 상태가 비고, 그때까지의 변경은 "누가 고쳤는지 모르는 변경"이 된다. 이런 변경은 빌드 1·2 에는 들어가고 빌드 3(정상 버전 + 내 변경만)에서는 빠진다. 실행에서 조용히 빠지지는 않는다.
- **같은 파일을 여럿이 고치면 줄 단위로 가르지 않는다.** 파일 통째로 넣거나 뺀다. 내가 함께 고친 파일은 다른 사람을 제외해도 되돌리지 않으므로, 그 파일에서 난 에러는 `sharedFiles` 로 알려 줄 뿐 피해 가지 못한다.
- **문법 검사는 Java 만.** 다른 언어 작성자는 항상 `READY` 다. 또 문법만 보므로(타입·없는 메서드는 안 봄) `READY` 인데도 컴파일이 깨질 수 있다. 그 경우는 빌드 2 가 잡는다.
- **안전 실행은 Java 콘솔·Spring Boot 만.** 디버그는 적용 대상이 아니다.
- **Spring Boot 는 차례를 기다린다.** 동시에 정해진 개수만 돌고, 앞사람이 앱을 내릴 때까지 기다린다. 기다리는 시간에 상한은 없다(11절).
- **서버 직접 반영의 틈.** `apply-direct` 는 방이 닫혔는지 확인하고 쓰지만, 그 직전에 디스크 내용을 읽어 간 사람이 직후에 방을 여는 경우까지는 막지 못한다. 틈은 수십 ms 다.
- **팀이 지운 파일을 내가 샌드박스에서 고쳤다면** `NEW_FILE` 로 판정되어, 반영하면 그 파일이 다시 생긴다.
- **서버 동작이 하나 바뀌었다(버그 수정).** 협업 자동 저장(`allowEmpty: false`)은 이제 **없는 파일을 새로 만들지 않는다.** 예전에는 팀원이 파일을 지우거나 이름을 바꾸면, 그 파일을 열어 둔 사람의 탭이 닫히면서 나가는 마지막 저장이 지운 파일을 되살렸다. 사용자가 직접 누른 저장·실행 전 저장·"모두 저장"은 지금처럼 없는 파일을 만든다. **프론트는 협업 자동 저장에만 `allowEmpty: false` 를 싣는 지금 규칙을 유지해야 한다.**

---

## 11. 서버 설정

| 키 (환경변수) | 기본값 | 뜻 |
| --- | --- | --- |
| `ide.safe-run-root` (`IDE_SAFE_RUN_ROOT`) | `C:\WebIDE\safe-run` | green · sandbox · runs 폴더의 루트. **운영(Linux)에서는 반드시 지정** |
| `ide.safe-run.max-builds.console` | `3` | Java 콘솔의 실행 1회당 최대 빌드 횟수 |
| `ide.safe-run.max-builds.spring-boot` | `3` | Spring Boot 의 최대 빌드 횟수. `2` 로 낮추면 빌드 1 → 빌드 3 만 한다 |
| `ide.safe-run.spring-boot-concurrency` (`IDE_SAFE_RUN_SPRING_BOOT_CONCURRENCY`) | `1` | 서버 전체에서 동시에 도는 Spring Boot 안전 실행의 수. 넘치면 대기열에서 기다린다 |
| `ide.safe-run.build-timeout-seconds` | `600` | 빌드 한 번을 기다리는 최대 시간 |
| `collab.auth.enabled` | `true` | 끄면 작성자 구분이 동작하지 않는다(기동 시 경고). `sbx` 방은 꺼도 본인 확인을 한다 |

폴더 구조:

```
{safe-run-root}/{workspaceId}/green/{project}/{branchFolder}/              정상 버전
{safe-run-root}/{workspaceId}/green/{project}/{branchFolder}.stamp         갱신 시각
{safe-run-root}/{workspaceId}/sandbox/{project}/{branchFolder}/{userId}/   files/ · base/ · meta.json
{safe-run-root}/runs/{runId}/                                              실행용 임시 폴더 (끝나면 삭제)
```

워크스페이스를 지우면 `{safe-run-root}/{workspaceId}` 도 함께 지워진다.

### 운영 배포 전 할 일

**1. `IDE_SAFE_RUN_ROOT` 를 지정한다.** 기본값이 Windows 경로(`C:\WebIDE\safe-run`)다. 지정하지 않고 Linux 에서 띄우면 서버를 실행한 폴더 아래에 `C:\WebIDE\safe-run` 이라는 이름의 폴더가 생긴다. `IDE_USERS_ROOT` 를 지정하는 곳(systemd 유닛의 `Environment=`, 또는 실행 스크립트)에 나란히 적는다.

```bash
IDE_SAFE_RUN_ROOT=/opt/webide/safe-run
```

사용자 개인 폴더(`IDE_USERS_ROOT`) **밖**이어야 한다. 안에 두면 사용자의 폴더 선택 창에 보인다.

**2. 폴더를 만들고 권한을 준다.** 이 폴더를 읽고 쓰는 것은 **백엔드(Java) 프로세스뿐**이다. 컨테이너에는 백엔드가 Docker API 로 파일을 복사해 넣으므로(폴더를 마운트하지 않는다) Docker 쪽에 따로 권한을 줄 필요는 없다.

```bash
sudo mkdir -p /opt/webide/safe-run
sudo chown -R webide:webide /opt/webide/safe-run   # webide = 백엔드를 실행하는 계정
sudo chmod 750 /opt/webide/safe-run                # 다른 계정이 팀의 소스 사본을 못 읽게
```

- 폴더가 없으면 백엔드가 처음 쓸 때 만들려고 한다. 상위 폴더(`/opt/webide`)에 쓰기 권한이 없으면 그때 실패하고, 증상은 "실행하면 `[Error] 안전 실행 중 오류`" 와 "샌드박스 API 가 500" 이다. 미리 만들어 둔다.
- 여기에는 브랜치마다 소스 사본(green)이 하나씩 쌓인다. 빌드 산출물과 `node_modules` 는 복사하지 않으므로 크지 않지만, 디스크 여유를 워크스페이스 폴더와 같은 기준으로 본다.
- 샌드박스 반영의 3-way 병합이 `git merge-file` 을 쓴다. `git` 은 기존 Git 기능 때문에 이미 깔려 있어야 한다.

**3. Spring Boot 동시 실행 수를 정한다.** 측정해 보면 Spring Boot 안전 실행 하나가 컴파일 직후에 약 700MB 를 쓰고(gradle 데몬이 약 550MB), 그 위에 앱 자체의 메모리가 더해진다. **2GB 서버(t4g.small)에서는 기본값 `1` 을 그대로 둔다.** 메모리를 늘린 서버에서만 올린다(실행 하나당 1GB 로 잡는다).

```bash
IDE_SAFE_RUN_SPRING_BOOT_CONCURRENCY=1
```

- 자리는 빌드할 때뿐 아니라 **앱이 떠 있는 동안 내내** 차지한다. 누군가 Spring Boot 앱을 띄워 놓고 중지하지 않으면 다음 사람은 계속 기다린다.
- 이 제한은 안전 실행에만 걸린다. **토큰 없이 보내는 예전 방식의 실행은 대기열을 거치지 않는다.** 프론트 전환(9절 1단계)이 끝나기 전에는 Spring Boot 실행이 겹칠 수 있다.
- Java 콘솔은 가벼워서 제한하지 않는다.

**4. (선택) 빌드 횟수.** 한 번의 실행은 컨테이너 하나를 재사용하므로 두 번째 빌드부터는 몇 초다. 로컬 측정으로 Spring Boot 는 빌드 1회에 약 36초, 최악(3회)에도 약 33초였다(대부분이 컨테이너를 띄우고 gradle 이 처음 도는 시간이다). 그래서 기본값 3회를 줄일 이유는 없다. arm64 2GB 에서는 절대 시간이 더 길 것이므로 배포 후 한 번 재 본다.

---

## 12. 전환이 끝난 뒤 제거할 것

프론트가 새 방식으로 다 옮기고 서버 로그에 `[Deprecated]` 가 더 안 찍히면, 아래를 별도 작업으로 지운다. **옛 방식으로 만든 `focus-u…` 브랜치에 반영 안 된 작업이 남아 있지 않은지 먼저 확인한다** — API 를 지우면 그 브랜치는 평범한 브랜치로만 남고, 일반 브랜치 병합으로 합쳐야 한다(프론트의 `focus-` 숨김 처리도 같이 지워야 목록에 보인다).

### 백엔드 (`demo/src/main/java/com/myide/backend`)

| 파일 | 지울 것 | 비고 |
| --- | --- | --- |
| `controller/GitController.java` | `createSandbox(...)` — `POST /api/git/sandbox/create` | |
| `controller/GitController.java` | `applySandbox(...)` — `POST /api/git/sandbox/apply` | |
| `controller/GitController.java` | `sanitizeBranchSegment(...)` | 위 두 메서드만 쓴다 |
| `controller/GitController.java` | `@Slf4j` 와 그 import | 경고 로그 때문에 넣은 것이다. 다른 곳에서 `log` 를 쓰게 됐다면 남긴다 |
| `handler/WorkspaceEventWebSocketHandler.java` | `broadcastFileTreeChanged(request, action, revision)` 3인자 오버로드와 `FileTreeChangedMessage.revision` 필드 | `applySandbox` 만 `revision` 을 넘긴다. 지운 뒤 2인자 메서드가 직접 방송하게 합친다. 프론트가 `revision` 을 읽지 않는지 먼저 확인 |
| `dto/ide/FileRequest.java` | `checkoutAfterCreate` 필드 | `createSandbox` 외에 `ProjectService.createBranch` 가 읽는지 확인한 뒤 판단한다 |

남기는 것: `GitService.mergeBranchInto` · `getHeadHash` · `deleteBranch`, `ProjectService.createBranch` — 일반 브랜치 병합·생성이 쓴다.

참고: 프론트의 `resolveSandboxConflictApi`(`POST /api/git/sandbox/resolve-conflict`)에 대응하는 백엔드 엔드포인트는 **지금도 없다.** 지울 것이 없다.

### 프론트 (`IDE-front/src`)

| 파일 | 지울 것 |
| --- | --- |
| `lib/ide/api.js` | `createSandboxApi`, `applySandboxApi`, `resolveSandboxConflictApi` |
| `hooks/ide/useGitBranches.js` | `createSandbox`, `applySandbox`, `extractSandboxBranchName`, `sanitizeSandboxTaskName`, `isSandboxBranch`, `isOwnSandboxBranch`, `isSandboxMode` 계산, `visibleBranches` 의 샌드박스 거름 |
| `components/ide/git/GitBranchControls.jsx` | `canCreateSandbox`, `executeCreateSandbox`, `handleOpenSandboxConflictStatus`, 샌드박스 생성 모달, 샌드박스 병합 모달, 충돌 모달, `getBranchMeta` 의 샌드박스 색, `wevaisPendingSandboxCleanup` 쓰기 |
| `components/ide/GitDashboard.jsx` | `isSandboxBranchName`, `cleanupPendingSandboxBranch`, `SANDBOX_CLEANUP_STORAGE_KEY`(`wevaisPendingSandboxCleanup`), `visibleBranchList` 의 샌드박스 거름, `waivs:open-git-status` 가 샌드박스 충돌용으로만 쓰였다면 그 핸들러 |
| `components/ide/TeamIdeMain.jsx` | `isSandboxMode` 인라인 판정(브랜치 이름 기준) → `State.enabled` 기준으로 교체 |
| `components/ide/git/BranchSelector.jsx` | 파일 전체. 지금도 import 하는 곳이 없다 |
| `tsconfig.json` | 위에서 지운 `.js`/`.jsx` 파일이 `include` 에 있으면 그 항목 |

브랜치 이름 검증(`validateBranchName`)에서 `:` 를 막는 규칙은 **남긴다.** 방 이름 구분자라 풀면 `sbx` 방 이름까지 깨진다.
