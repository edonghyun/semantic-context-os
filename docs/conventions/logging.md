# 로깅 규약 (Java · SCOS)

이 문서는 운영 로그의 형식, 이벤트 이름, 수준, 실릴 수 있는 값, 파일 보존, 이벤트 카탈로그를 정한다.
결정의 근거와 기각한 대안은 [ADR-0031](../migration/decisions/ADR-0031-logging-policy.md)에 있다.
질의 로그(`SCOS_QUERY_LOG`)는 이 문서가 다루지 않는다. 형식은 ADR-0023 §3이 정한다.
규칙 등급(**[MUST]**·**[SHOULD]**·**[MAY]**)과 "자동 검사:" 줄의 뜻은 [coding-guidelines.md](coding-guidelines.md)와 같다.
규칙 ID는 `LG-<절 번호>`다. 문서와 게이트가 어긋나면 게이트가 실제 동작이다. 그때는 문서를 고친다.
단, §9 카탈로그와 코드가 어긋나면 게이트가 실패한다. 이 경우에는 둘을 같은 커밋에서 함께 고친다.

**한눈에**

| 무엇 | 규칙 |
|---|---|
| 사람이 보는 출력 | Spring Boot 기본 콘솔 형식 + 색. 터미널·CLI·데몬 `serve.log` |
| 기계가 읽는 출력 | JSON 한 줄. 데몬 `serve.jsonl`. `status --log`가 읽는다 |
| 운영 코드의 warn·error | `EventLog`로만 쓴다 |
| 이벤트 이름 | `영역.대상.동작`, 소문자 점 표기. detail 키는 camelCase |
| 실패 위치 | 오류·거부 이벤트에 `operation`과 `providerLocator` |
| 금지 | 토큰·질의 원문·문서 본문. 불투명 커서는 sha256 앞 8자 지문으로 |
| 보존 | 하루 단위 회전, 14일 보관 |

---

## 1. 출력 두 갈래

같은 로그 이벤트를 두 형식으로 쓴다. 사람이 읽는 형식은 읽기 쉽게 바꿀 수 있다. 기계가 읽는 형식은 줄 모양을 바꾸지 않는다.

| 갈래 | 형식 | 어디에 | 누가 읽나 |
|---|---|---|---|
| 사람 | Spring Boot 기본 콘솔 형식 + ANSI 색 | 명령·`mcp`의 stderr, 데몬은 `$SCOS_LOG_DIR/serve.log`(stderr에는 쓰지 않는다) | 운영자(`tail`·`less -R`), 터미널 |
| 기계 | JSON 한 줄(§1.2) | 데몬의 `$SCOS_LOG_DIR/serve.jsonl` | `status --log`, 로그 분석 |

- **[MUST]** stdout에는 로그를 쓰지 않는다. stdout은 MCP JSON-RPC(ADR-0010)와 조회 명령 결과(ADR-0024) 전용이다 —
  자동 검사: `McpStdioProcessTest`(stdout 줄은 모두 JSON-RPC), `ArchitectureRulesTest.noDirectStdStreamsOutsideMcp`
- **[MUST]** 데몬은 두 파일을 logback 파일 appender로 직접 쓰고 stderr에는 로그를 쓰지 않는다. launchd의 stderr 넘김(`logs/serve.stderr.log`)은 래퍼 줄과 logback이 뜨기 전·JVM이 죽을 때의 출력만 받는다 —
  자동 검사: `LogbackOutputTest`, `DaemonProcessTest`(stdout·stderr에 로그 없음)
- **[SHOULD]** 기계 갈래를 읽는 도구(`status --log`, 분석 스크립트)는 `serve.jsonl`을 읽는다. 사람 갈래의 텍스트를 정규식으로 파싱하지 않는다.
  사람 갈래 형식은 예고 없이 바뀔 수 있다

### 1.1 사람 갈래 — Spring Boot 기본 콘솔 형식

```text
2026-10-27T10:15:03.551+09:00 ERROR 4821 --- [scos] [ collector-2] i.s.c.i.CollectionRuntime  : collection.run.failed source=kep-notion run=8dff121d status=failed errorKind=invalid_provider_response errorSummary="Notion API error: invalid_provider_response" operation=notion.blocks.children.list violation=item_shape itemIndex=3 workKind=block_children blockId=104f…
2026-10-27T10:15:07.002+09:00  INFO 4821 --- [scos] [    scheduler] i.s.server.DaemonMaintenance             : maintenance.sweep.completed scanned=120 deleted=3 retainedByReference=110 retainedByGrace=7 bytesFreed=40960 changesPruned=0
2026-10-27T10:15:09.410+09:00  WARN 4821 --- [scos] [ HikariPool-1] com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Thread starvation or clock leap detected
```

- 줄 모양은 Spring Boot 기본 콘솔 형식이다: 시각(ISO-8601, 오프셋 포함) · 수준 · PID · `---` · `[애플리케이션 이름]` · `[스레드]` · 줄인 로거 이름 · `:` · 메시지.
- 색은 Spring Boot 기본 배색이다: 수준(error 빨강, warn 노랑, info·debug 초록), PID 자홍, 로거 청록, 구분자 흐리게. 켜는 조건은 `spring.output.ansi.enabled`다: 명령 `detect`, `mcp` `never`, 데몬 `always`(`serve.log`에도 색 코드를 쓴다. `less -R`로 읽는다).
  `detect`는 Spring Boot `AnsiOutput`이 `System.console()`로 정한다 — Java 21에서는 **stdin과 stdout이 둘 다 터미널**일 때만 콘솔이 있다(JDK 22+에서는 `Console.isTerminal()`로 같은 조건을 본다). 로그가 가는 stderr는 보지 않는다.
  그래서 `status | jq`처럼 stdout을 파이프로 넘기면 stderr가 터미널이어도 색이 없고, `status 2> err.log`처럼 stderr만 파일로 넘기면 그 파일에 색 코드가 들어간다.
- 설정은 `logback-spring.xml`이 Spring Boot의 `defaults.xml`(`CONSOLE_LOG_PATTERN`)을 그대로 쓴다. 줄 모양을 따로 정의하지 않는다.
- **이벤트 줄의 메시지**는 `이벤트이름 key=value …`다.
  - 순서: 이벤트 이름 → `source=<sourceInstanceId>` → `run=<runId>` → detail 키를 넣은 순서대로.
  - detail 값이 객체이면 펼쳐서 그 안의 키를 그대로 쓴다(`providerLocator{workKind, blockId}` → `workKind=… blockId=…`, `blob{scanned, …}` → `scanned=… …`). 펼친 키가 같은 줄의 다른 키와 겹치면 바깥 키를 점으로 앞에 붙인다(`blob.scanned=…`).
  - 배열은 `[a,b,c]`로 쓴다. 공백·`=`·따옴표가 든 문자열 값은 JSON 문자열처럼 큰따옴표로 감싸고 이스케이프한다(`errorSummary="Notion API error: …"`).
  - 원인 예외가 있으면 Spring Boot처럼 메시지 줄 아래에 스택을 쓴다.
- 이벤트가 아닌 줄(프레임워크 로그)은 로거가 넘긴 메시지를 그대로 쓴다.

### 1.2 기계 갈래 — JSON 한 줄

줄 모양은 ADR-0023의 것을 그대로 둔다. 키 순서도 아래와 같다. `?`는 값이 있을 때만 쓴다는 뜻이다.

```text
{"ts","level","event","sourceInstanceId"?,"runId"?,"detail"?,"error"?}
```

```json
{"ts":"2026-10-27T01:15:03.551Z","level":"error","event":"collection.run.failed","sourceInstanceId":"kep-notion","runId":"8dff121d-…","detail":{"status":"failed","errorKind":"invalid_provider_response","errorSummary":"Notion API error: invalid_provider_response (cursor cycle: list=blocks:104f… trail=[-,a1b2c3d4,9f8e7d6c] next=a1b2c3d4 length=3)","operation":"notion.blocks.children.list","violation":"cursor_cycle","providerLocator":{"workKind":"block_children","blockId":"104f…"}}}
{"ts":"2026-10-27T01:15:09.410Z","level":"warn","event":"log","detail":{"logger":"com.zaxxer.hikari.pool.HikariPool","message":"HikariPool-1 - Thread starvation or clock leap detected"}}
```

- `ts`는 UTC ISO-8601 밀리초, `level`은 `debug`·`info`·`warn`·`error`다(TRACE는 debug로 쓴다).
- `error`는 원인 예외가 있을 때 `{"type","message","stack"}`이다.
- 이벤트가 아닌 줄은 `event`가 `"log"`이고 `detail`이 `{"logger","message","error"?}`다.
- MDC와 그 밖의 SLF4J 키-값은 쓰지 않는다. `EventLog`가 넘긴 값 밖의 것이 섞여 들어갈 통로를 두지 않는다.
- **[MUST]** 이 줄 모양(키 이름·순서·`event:"log"` 규약)을 바꾸려면 ADR을 먼저 고친다. `status --log`와 쌓인 로그 분석이 이 모양에 기댄다 —
  자동 검사: `JsonLineEncoderTest`
- TS와의 바이트 동등은 의무가 아니다(ADR-0031). 값의 직렬화 세부(숫자 표기 등)는 바뀔 수 있다.

---

## 2. 이벤트 이름과 detail 키

### 2.1 [SHOULD] 이벤트 이름은 `영역.대상.동작`

- 소문자 점 표기, 마디 셋이다. `영역`은 일이 일어나는 곳(`collection`·`lease`·`supervisor`·`maintenance`), `대상`은 무엇에 대해(`run`·`item`·`heartbeat`·`snapshot`), `동작`은 무슨 일이(과거분사나 상태: `failed`·`completed`·`rejected`·`slow`).
- 한 마디 안에서 단어가 둘 이상이면 밑줄로 잇는다(`collection.snapshot.reconcile_guarded`).
- 이름은 그 사건 하나를 가리킨다. 같은 이름을 두 수준으로 쓰지 않는다. 수준이 다른 사건은 이름을 나눈다(`collection.run.failed` / `collection.run.cancelled`).
  예외는 주기 요약 하나다: `daemon.cycle.completed`는 같은 사건(사이클 끝)을 §3 규칙대로 info나 debug로 쓴다. 분석이 이름 하나로 모든 사이클을 찾게 하려는 것이다.
  이 이벤트는 §7의 "이벤트 하나는 호출 지점 하나"에서도 예외다: `DaemonObserver`가 수준을 삼항으로 골라 `EventLog.info(…)`·`EventLog.debug(…)` 두 호출 지점에서 같은 이름(같은 파일 상수)으로 쓴다(게이트는 두 지점을 모두 읽어 수준 집합 `info / debug`로 대조한다).
  명령의 수집 실패 둘(`command.collect.failed`·`command.cycle.failed`)도 같은 모양의 예외다(`info / error`). 같은 사건(명령의 수집 실패, 종료 코드 1)인데,
  run이 실패해 error `collection.run.failed`가 이미 남았으면 info, run 밖의 실패(예외)라 다른 error 신호가 없으면 error다. 같은 실패가 error 두 줄로 나오지 않게 하면서, 명령 실패는 이름 하나로 찾게 한다.
- 이름은 공개 계약이다. 운영 분석과 `status --log`가 이름으로 찾는다. 이름을 바꾸면 §9 카탈로그와 ADR 근거를 함께 남긴다.

자동 검사: `EventNameRulesTest`(이름은 문자열 리터럴이나 같은 파일의 `static final String` 상수이고 형식에 맞다), `EventCatalogTest`(§9 등재). §11 참고.

### 2.2 [SHOULD] detail 키는 camelCase, 값은 짧은 사실

- 키는 camelCase 영어(`errorKind`·`elapsedMs`·`unobservedCount`). 단위가 있는 수는 단위를 키에 붙인다(`elapsedMs`·`bytesFreed`).
- 값은 문자열·수·불리언·배열·객체다. 객체는 한 단계까지만 쓴다(`providerLocator`·`blob`).
- 공통 키는 뜻을 하나로 쓴다.

| 키 | 뜻 |
|---|---|
| `status` | run 상태 wire 값(`failed`·`cancelled`) |
| `errorKind` | `CallFailure.Kind`의 wire 값 등 분류 |
| `errorSummary` | 오류 요약. 500자 상한(`RunFailure.loggedSummary`) |
| `message` | 사람이 읽는 한 줄 사유(원문이 아닌 것) |
| `failureKind` | 거부 종류(`RejectedItem.FailureKind`의 wire 값) |
| `operation` | 실패한 공급자 호출 이름(§4) |
| `providerLocator` | 공급자 쪽 위치(§4) |
| `violation` | 공급자 응답이 어긋난 검사 이름(§4, `CallFailure.Violation.check`) |
| `itemIndex` | 어긋난 항목의 응답 안 인덱스(0부터, 항목 검사일 때만) |

- `sourceInstanceId`·`runId`는 detail이 아니라 `EventLog.source(…)`·`.run(…)`으로 싣는다.

자동 검사: 리뷰. 신호: snake_case 키, 단위 없는 시간·크기 키, 같은 뜻을 다른 키로 쓴다.

---

## 3. 수준의 뜻

| 수준 | 뜻 | 예 |
|---|---|---|
| error | **사람이 봐야 한다.** 스스로 회복하지 못했다 | run 실패, 데몬 종료, 정비 실패, 반복된 스냅샷 보호 |
| warn | **스스로 회복했지만 추세를 봐야 한다** | 항목 거부, heartbeat 느림·실패, 스냅샷 보호 |
| info | **상태 전이** | run 시작·끝, 기동, 정비 완료 |
| debug | **개발용.** 운영에서는 끈다 | 바뀐 것 없는 사이클 요약 |

- **[SHOULD]** 수준은 위 뜻으로 고른다. "중요해 보여서" 올리지 않는다. error가 쌓이면 사람이 무뎌진다.
- **[SHOULD]** 주기적인 요약(데몬 사이클)은 직전과 달라졌거나 실패가 있을 때만 info, 나머지는 debug다. 운영 로그의 대부분을 주기 요약이 채우지 않게 한다.
- 운영 설정의 수준: 루트 `WARN`, `io.scos` `INFO`. debug는 개발할 때만 켠다.

자동 검사: `EventCatalogTest`(코드의 수준 = §9 표의 수준). 수준 선택은 리뷰. 신호: 회복되는 일에 error, 주기마다 나는 info.

---

## 4. 실패 위치 — `operation`과 `providerLocator`

- **[MUST]** 공급자에서 비롯한 오류·거부 이벤트(`collection.run.failed`·`collection.item.rejected` 등)는 detail에 위치를 싣는다.
  - `operation`: 실패한 공급자 호출 이름. `CallFailureException.operation()`과 같은 값이다(`slack.users.conversations`, `notion.blocks.children.list`). 지표의 operation 태그와 같은 이름을 쓴다.
  - `providerLocator`: 공급자 쪽에서 어디였는지를 담은 객체.

    | 키 | 뜻 | 예 |
    |---|---|---|
    | `workKind` | 실패·거부한 작업의 종류(Notion 작업 큐의 `kind`) | `page`, `block_children`, `data_source_rows` |
    | 대상·항목 id | 그 작업의 대상이나 거부된 항목의 공급자 id(키 이름은 커넥터가 정한다) | `pageId`, `blockId`, `dataSourceId`, `fileId`, `issueKey` |
    | `list` | 걷던 목록 `<종류>:<id>`(CONTRACTS F12-D1의 목록 이름) — 예약. 지금은 커서 순환의 걷기 설명(errorSummary 끝 괄호)에만 있다 | `blocks:104f…`, `history:<채널>` |
    | `cursor`·`cursorTrail` | 요청한 커서의 지문(§5)·순환의 지문 순서 — 예약. 위와 같다 | `a1b2c3d4` |

  - `violation`·`itemIndex`: 공급자 응답이 형식 검사에서 어긋났으면(`invalid_provider_response`) 어느 검사였는지(`results_not_array`·`item_shape`·`cursor_cycle` …)와,
    항목 검사면 응답 안 항목의 인덱스. 검사 이름은 커넥터가 정하는 snake_case 상수이고 응답 본문·값은 싣지 않는다(§5). 지금 Notion·Slack이 단다(CONTRACTS F12-D1·F17).

- **[MUST]** 공급자 예외는 위치를 메시지 문자열이 아니라 구조(필드)로 들고 다닌다. 이벤트를 쓰는 쪽은 그 필드를 detail로 옮긴다.
  메시지에서 위치를 정규식으로 꺼내지 않는다(CG-5 "분류는 타입과 필드로").
  - 통로: `operation`은 `CallFailureException.operation()`, 위치는 커넥터가 `CallFailureException.at(locatorJson)`으로 붙인다(실행기 위 — 작업을 아는 곳.
    거부의 `RejectedItem.providerLocatorJson`과 같은 JSON 표현), 위반은 분류 결과 `CallFailure.violation()`이다(실행기 아래 — 응답을 검사한 곳).
    `RunFailure`가 셋을 들고 `CollectionRuntime`이 `collection.run.failed` detail로 옮긴다. run 저장값(errorKind·errorSummary)에는 쓰지 않는다.
- 위치를 모르는 실패(설정 오류, DB 오류)에는 `providerLocator`를 쓰지 않는다. 빈 객체도 쓰지 않는다.

자동 검사: 오류·거부 이벤트 지점의 테스트가 `operation`·`providerLocator`를 단언한다(§10). 리뷰 신호: `errorSummary`만 있고 위치가 없는 공급자 오류, 예외 메시지에 목록 id를 이어 붙인다.

---

## 5. 비밀·원문 금지와 커서 지문 (H6)

- **[MUST]** 로그(두 갈래 모두)와 오류 메시지에 아래를 넣지 않는다.
  - 비밀값: 토큰, API 키, 비밀번호, 자격 증명 파일 내용, 인증 헤더
  - 질의 원문(`search_context`의 질의)
  - 문서 본문: 페이지·메시지·이슈·파일의 내용, 공급자 응답 본문
  - 비밀이 든 URL 전체(쿼리 문자열의 토큰 등)
- **한정 예외(다중 사용자 설계 §4.4)**: `mcp.tool.failed`·`mcp.tool.rejected`의 원인 예외(`error`·스택)는 진단을 위해 원문을 보존하므로 저장소·공급자 메시지에 포함된 질의가 남을 수 있다. detail에는 질의·본문을 더하지 않으며, 클라이언트에는 고정 문구와 참조 ID만 응답한다. 토큰·자격 증명 금지는 이 예외에도 적용한다. 운영 로그 접근·보존은 이 원문 가능성을 전제로 제한한다.
- **허용**: 공급자·SCOS의 id(소스·run·페이지·블록·채널·파일 id), 종류·상태 값, 수, 공급자 오류 코드(`object_not_found`), 호스트와 경로(쿼리 문자열 없이).
- **[MUST]** 불투명 커서(공급자가 준 다음 페이지 토큰)는 그대로 쓰지 않는다. **sha256 앞 8자(소문자 16진수) 지문**으로 쓴다. 첫 페이지(커서 없음)는 `-`다.
  같은 커서는 늘 같은 지문이라 순환과 반복을 알아볼 수 있고, 커서 안에 든 값은 새지 않는다. 지문을 만드는 곳은 `CursorTrail` 하나다.
- 걸러 넣는 책임은 이벤트를 쓰는 쪽에 있다. `EventLog`와 인코더는 값을 검사하지 않는다.
- 비밀을 담는 record는 `toString`을 재정의해 값을 가린다(CG-10).

자동 검사: 리뷰. 신호: detail·메시지에 `body`·`text`·`query`·`token`·`url` 값을 그대로 넣는다, 예외 메시지(`e.getMessage()`)를 출처 확인 없이 detail에 싣는다, `cursor` 값이 8자가 아니다.

---

## 6. 운영 코드의 warn·error는 `EventLog`로만

- **[MUST]** 운영 코드(`backend/*/src/main`)가 warn·error를 쓸 때는 `io.scos.engine.EventLog`를 쓴다. 이벤트 이름이 없는 warn·error는 분석에서 찾을 수도 셀 수도 없다.
- 평문 로그(`log.info(…)`, 기계 갈래의 `event:"log"`)는 프레임워크(Spring·Hikari·Flyway)와 운영 코드의 info·debug 진단용이다.
- CLI 명령이 사람에게 보여 줄 결과는 로그가 아니라 `CommandOutput`(stdout)이다. 사용법 오류 문장(`import: --embedding-generation is required …`, 종료 코드 2)도 로그가 아니라 `CommandOutput.stderr()`로 그 문장 그대로 쓴다 — 사용자가 읽는 CLI 메시지라 로그 줄 모양으로 바꾸지 않는다.

```java
// 나쁨 — 이름이 없어 찾을 수 없고, 값이 메시지 문자열에 섞인다
log.warn("projection of {} failed {} times in a row: {}", id.value(), n, error);

// 좋음 — 이름·소스·detail이 따로 실린다(§9의 projection.request.failed_repeatedly. 새로 더할 때는 §10 절차)
EventLog.error(log, "projection.request.failed_repeatedly")
    .source(id)
    .detail("consecutiveFailures", n, "errorSummary", error)
    .log();
```

자동 검사: `LoggingRulesTest.noDirectWarnOrError`(ArchUnit). `EventLog` 밖에서 SLF4J `Logger`(넓힌 타입 포함)의 `warn`·`error`·`atWarn`·`atError`·`atLevel`·`makeLoggingEventBuilder`·`log`(`LocationAwareLogger.log`) 접근을 막는다 — 호출과 메서드 참조(`list.forEach(log::warn)`) 둘 다다. 수준을 값으로 받는 진입점(`atLevel`·`makeLoggingEventBuilder`·`log`)은 정적으로 가릴 수 없어 통째로 막는다(info·debug는 `log.info`·`atInfo`로). 기존 위반은 고정 목록에 둔다(지금 0건). §11 참고.

---

## 7. `EventLog` 쓰는 법

```java
EventLog.error(log, "collection.run.failed")   // 수준과 이름
    .source(id)                                // sourceInstanceId(있으면)
    .run(runId.value())                        // runId(있으면)
    .detail(                                   // 넣은 순서가 출력 순서다
        "status", status.wire(),
        "errorKind", failure.kind(),
        "errorSummary", failure.loggedSummary())
    .cause(e)                                  // 원인 예외(있으면) — 스택이 실린다
    .log();
```

- 수준은 `EventLog.debug`·`info`·`warn`·`error`로 고른다.
- `detail(k1, v1, k2, v2, …)`로 키와 값을 바로 싣거나 `detail(Map)`으로 객체를 싣는다. 순서가 출력 순서다. 중첩 객체를 만드는 내부 코드는 `EventValues.detail(…)`을 쓴다.
- 이름은 문자열 리터럴이나 같은 파일의 `static final String` 상수로 넘긴다. 이어 붙이거나 다른 클래스의 상수로 넘기면 게이트가 이름을 읽지 못해 실패한다.
- `EventLog`는 `EventLog.warn(…)`처럼 타입 이름으로 부른다. 멤버를 static import(`import static …EventLog.warn;`)하지 않는다. 게이트가 `EventLog.<수준>(` 모양으로 호출을 찾기 때문이다 — 자동 검사: `EventNameRulesTest.noStaticImportsOfEventLog`
- 이벤트 하나는 호출 지점 하나에서 쓴다. 같은 이름을 여러 곳에서 쓰면 카탈로그의 "위치"가 흐려진다. 예외는 `daemon.cycle.completed`와 `command.collect.failed`·`command.cycle.failed`다(§2.1).

---

## 8. 파일 회전·보존

| 파일 | 내용 | 회전 | 보존 |
|---|---|---|---|
| `$SCOS_LOG_DIR/serve.log` | 데몬 사람 갈래(§1.1) | 하루 단위(날짜 접미사) | 14일 |
| `$SCOS_LOG_DIR/serve.jsonl` | 데몬 기계 갈래(§1.2) | 하루 단위(날짜 접미사) | 14일 |

- logback 파일 appender(시간 기반 회전)가 둘 다 맡는다. 래퍼·launchd는 회전하지 않는다. 지난 파일 이름은 `serve.<yyyy-MM-dd>.log`·`serve.<yyyy-MM-dd>.jsonl`이다.
- `totalSizeCap`은 파일마다 500MB다. 평소 양(하루 수 MB)으로는 닿지 않는다. 오류 폭주 때 디스크를 지키는 상한이다. 상한은 지난 파일에만 걸리고, 오늘 파일은 하루 동안 자란다.
- 디렉터리는 `scos.log.dir`(환경변수 `SCOS_LOG_DIR`, 기본 `logs`)이다. 래퍼는 `$SCOS_HOME/logs`를 넘긴다.
- 14일이 지난 파일은 logback이 지운다. 더 오래 남길 기록은 로그가 아니라 DB(run·rejection 기록)에 있다.
- 로그 디렉터리는 소유자만 읽는다(`scos_private_dir`, ADR-0027).

---

## 9. 이벤트 카탈로그

운영 코드가 쓰는 `EventLog` 이벤트 전부다. 위치는 `backend/` 아래 경로의 클래스다.
**코드에 이벤트를 더하거나 이름·수준·detail 키를 바꾸면 이 표를 같은 커밋에서 고친다.** 어긋나면 게이트가 실패한다.
`?`는 있을 때만 싣는 키다. 모든 이벤트가 `sourceInstanceId`·`runId`를 싣지는 않는다. "소스·run" 열이 싣는 것을 보여 준다.

| 이벤트 | 수준 | 위치 | 소스·run | detail 키 | 테스트 |
|---|---|---|---|---|---|
| `client.source.failed` | warn | `scos-client` `IncrementalCollector` | 소스 | `operation`·`errorType` | `ClientCollectionEventLogTest` |
| `client.projection.failed` | warn | `scos-client` `IncrementalCollector` | 소스 | `operation`·`pendingDocuments`·`errorType` | `ClientCollectionEventLogTest` |
| `client.document.failed` | warn | `scos-client` `IncrementalCollector` | 소스 | `operation`·`documentId`·`stage` | `ClientCollectionEventLogTest` |
| `client.index.open_retry` | warn | `scos-client` `ClientMain` | — | `operation`·`attempt`·`maxAttempts`·`errorType` | `ClientStartupEventLogTest` |
| `client.index.unavailable` | error | `scos-client` `ClientMain` | — | `operation` | `ClientStartupEventLogTest` |
| `client.state.failed` | error | `scos-client` `ClientMain` | — | `operation`·`errorType` | `ClientStartupEventLogTest` |
| `client.cycle.failed` | warn | `scos-client` `ClientMain` | — | `operation`·`errorType` | `ClientStartupEventLogTest` |
| `client.collection.shutdown_timed_out` | error | `scos-client` `ClientMain` | — | `operation`·`waitMillis` | `ClientShutdownEventLogTest` |
| `mcp.tool.failed` | error | `scos-mcp` `McpToolSpecs`(MASKED 임베딩 공급자 장애·인프라 예외·Error) | — | `tool`·`ref` (+ `error`) | `McpErrorExposureTest` |
| `mcp.tool.rejected` | warn | `scos-mcp` `McpToolSpecs`(MASKED 문서 부재·인자 검증 오류) | — | `tool`·`ref` (+ `error`) | `McpErrorExposureTest` |
| `http.token_usage.failed` | error | `scos-server` `TokenUsageRecorder`(주기·종료 사용 기록 반영 실패) | — | — (+ `error`) | `McpHttpUsageTest`, `TokenUsageShutdownTest` |
| `http.authentication.failed` | error | `scos-server` `PrincipalBearerFilter`(토큰 저장소 조회 장애) | — | `ref` (+ `error`) | `PrincipalBearerFilterTest` |
| `collection.run.failed` | error | `scos-collection` `CollectionRuntime`(run 마감 직전) | 소스·run | `status`·`errorKind`·`errorSummary`, 공급자 호출 실패면 `operation`, 응답 검사 위반이 알려졌으면 `violation`(검사 이름)·`itemIndex`(항목 검사일 때), 커넥터가 실패한 작업의 위치를 붙였으면 `providerLocator` | `CollectionRuntimeEventLogTest`, `CollectionRuntimeHeartbeatStartFailureTest`, `NotionAdapterFixtureTest`, `NotionApiClientTest`, `SlackAdapterFixtureTest` |
| `collection.run.cancelled` | info | `scos-collection` `CollectionRuntime`(run 마감 직전) | 소스·run | `status`·`errorKind`·`errorSummary` | `CollectionRuntimeEventLogTest`, `DaemonProcessTest` |
| `collection.item.rejected` | warn | `scos-collection` `CollectionRuntime`(페이지 커밋 뒤, 거부마다) | 소스·run | `failureKind`·`message`·`operation`?·`providerLocator`? | `CollectionRuntimeEventLogTest` |
| `collection.reconciliation.completed` | info | `scos-collection` `CollectionRuntime`(run 마감 뒤 원장 보고) | 소스·run | `eligiblePages`·`missedPages`·`missRatio`·`partial`·`discoveries`(Count 문자열 배열)·`lastSuccessAgeSeconds` | `ReconciliationReportingTest` |
| `collection.reconciliation.report_failed` | warn | `scos-collection` `CollectionRuntime`(run 마감 뒤 보고 실패, 마감 결과 유지) | 소스·run | — (+ `error`) | `ReconciliationReportingTest` |
| `notion.absence.deferred` | warn | `scos-connector-notion` `NotionAbsences`(부재 후보 검증 불가, 전체 정리 보류) | 소스 | `reason`(`UNAVAILABLE`·`LIMIT_EXCEEDED`·`UNKNOWN_OWNERSHIP`) | `NotionAbsencesEventLogTest` |
| `collection.run.snapshot` | info | `scos-collection` `SourceSupervisor`(스냅샷 모드 시도) | 소스 | — | `SupervisorEventLogTest` |
| `supervisor.source.error` | error | `scos-collection` `SourceSupervisor`(run 전 실패) | 소스 | `message` (+ `error`) | `SupervisorEventLogTest` |
| `lease.heartbeat.slow` | warn | `scos-collection` `LeaseHeartbeat`(renew가 주기 절반 초과) | 소스 | `elapsedMs` | `HeartbeatEventLogTest` |
| `lease.heartbeat.contended` | warn | `scos-collection` `LeaseHeartbeat`(renew 잠금 경합) | 소스 | `message` | `HeartbeatEventLogTest` |
| `lease.heartbeat.failed` | warn | `scos-collection` `LeaseHeartbeat`(renew 실패) | 소스 | `message` | `HeartbeatEventLogTest` |
| `collection.snapshot.reconcile_guarded` | warn | `scos-collection` `SnapshotShrinkGuard.report`(축소 보호로 정리 안 함) | 소스·run | `unobservedCount`·`activeCount`·`minActive`·`maxDeletePercent` | `SnapshotShrinkGuardReportTest`, `CollectProcessShrinkGuardTest` |
| `collection.snapshot.reconcile_guarded_repeatedly` | error | `scos-collection` `SnapshotShrinkGuard.report`(보호가 연달아) | 소스·run | `consecutiveCount`·`unobservedCount`·`activeCount` | `SnapshotShrinkGuardReportTest` |
| `collection.snapshot.shrink_accept_used` | warn | `scos-collection` `SnapshotShrinkGuard.report`(허용으로 정리) | 소스·run | `deletedCount`·`activeCount` | `SnapshotShrinkGuardReportTest` |
| `collection.snapshot.shrink_accept_expired` | warn | `scos-collection` `SnapshotShrinkGuard.report`(허용 표시 만료) | 소스·run | `acceptedAt` | `SnapshotShrinkGuardReportTest` |
| `collection.snapshot.shrink_accepted` | warn | `scos-collection` `SnapshotShrinkGuard.accept`(`source accept-shrink` 감사) | 소스 | — | `SnapshotShrinkGuardReportTest` |
| `collection.snapshot.report_failed` | error | `scos-collection` `RunCompletion`(커밋 뒤 보호 보고 실패) | 소스·run | — (+ `error`) | `SnapshotShrinkReportFailureTest` |
| `maintenance.sweep.completed` | info | `scos-server` `DaemonMaintenance` | — | `blob`{`scanned`·`deleted`·`retainedByReference`·`retainedByGrace`·`bytesFreed`}·`changesPruned` | `DaemonMaintenanceTest` |
| `maintenance.sweep.failed` | error | `scos-server` `DaemonMaintenance` | — | `message` (+ `error`) | `DaemonMaintenanceTest` |
| `projection.request.failed_repeatedly` | error | `scos-collection` `ProjectionRun`(실패 기록 뒤 연속 실패 수가 maxAttempts 이상) | 소스 | `consecutiveFailures`·`attempts`·`parked`·`errorSummary` | `ProjectionEventLogTest` |
| `search.rerank.fallback` | warn | `scos-engine` `DefaultContextEngine`(재정렬이 원 순서로 폴백) | — | `reason`(`provider_error`·`invalid_format`)·`message`?(공급자 오류 메시지) | `ContextEngineTest` |
| `daemon.cycle.failed` | error | `scos-server` `CollectionDaemon`(틱이 예외로 끝남. 루프는 계속) | — | `message` (+ `error`) | `CollectionDaemonEventLogTest` |
| `daemon.loop.died` | error | `scos-server` `CollectionDaemon`(루프가 `Error`로 끝남. 프로세스는 종료 코드 70) | — | `exitCode` (+ `error`) | `CollectionDaemonEventLogTest` |
| `daemon.stop.timed_out` | warn | `scos-server` `CollectionDaemon.stop`(worker·루프 합류가 상한 안에 끝나지 않음) | — | `shutdownTimeoutMs` | `CollectionDaemonEventLogTest` |
| `command.collect.failed` | info / error | `scos-server` `Commands.collectFailed`(`collect`·`cycle`의 수집이 run 실패 보고나 예외로 끝남). run 실패 보고면 info(error는 `collection.run.failed`), 예외면 error | 소스 | `status`?·`errorKind`?(run 실패 보고) 또는 `message`(예외) | `CommandsEventLogTest` |
| `command.collect.already_running` | error | `scos-server` `Commands`(`collect` 명령인데 이미 수집 중, 종료 코드 1) | 소스 | — | `CommandProcessTest` |
| `command.collect.skipped` | warn | `scos-server` `Commands`(`cycle`에서 이미 수집 중인 소스를 건너뜀) | 소스 | — | `CommandProcessTest` |
| `command.cycle.failed` | info / error | `scos-server` `Commands`(`cycle`에서 수집이 실패한 소스가 있음, 종료 코드 1). 실패가 모두 run 실패 보고면 info, 하나라도 예외면 error | — | `failedSources` | `CommandsEventLogTest` |
| `daemon.cycle.completed` | info / debug | `scos-server` `DaemonObserver`(사이클 끝마다). 숫자가 바로 앞 사이클과 다르거나 `failed`>0·`errorType`이 있으면 info, 나머지 debug | — | `attempts`·`failed`·`deferred`·`projections`·`errorType`?(사이클 예외의 클래스 이름) | `DaemonObserverTest` |

`(+ error)`는 원인 예외를 `.cause(…)`로 실어 기계 갈래에 `error` 객체, 사람 갈래에 스택이 붙는다는 뜻이다.

**래퍼 이벤트(게이트 밖).** 운영 래퍼(`deploy/java/bin/*.sh`)는 `EventLog`가 아니라 셸 함수(`scos-common.sh`의 `scos_log`·`scos_log_jsonl`)로 같은 JSON 줄 모양(§1.2, `detail`까지)을 쓴다.
위 표와 `EventCatalogTest`의 대상이 아니다 — 게이트는 Java 코드의 `EventLog` 호출만 대조한다. 그래서 이 목록은 표가 아니라 목록으로 둔다.
데몬 래퍼 `scos-daemon.sh`가 java가 끝난 뒤 쓰는 줄은 아래와 같다. java가 끝난 뒤라 logback과 같은 파일에 동시에 쓰지 않는다.

- `daemon.loop.died`(error, java 종료 코드 70)·`daemon.out_of_memory`(error, 3)·`daemon.exited`(error, 그 밖의 0 아닌 코드), detail `exitCode` —
  stderr(launchd는 `logs/serve.stderr.log`)와 `$SCOS_LOG_DIR/serve.jsonl` 둘 다에 쓴다. `status --log`가 `serve.jsonl`에서 읽는다.
  덧붙이기가 실패해도 래퍼의 종료 코드는 그대로다. `daemon.loop.died`는 위 표의 Java 이벤트(`CollectionDaemon`)가 먼저 남고 래퍼 줄이 뒤따른다.
- `daemon.stopped`(info, 0·130·143) — stderr에만 쓴다.
- `wrapper.env.*`·`wrapper.log_dir.permissions`(java를 띄우기 전)와 다른 운영 스크립트(`backup.*`·`restore.*`·`rollback.*`·`disk.*`)의 줄 — 그 스크립트의 stderr에만 쓴다.

자동 검사: `DaemonWrapperTest`(세 오류 줄이 `serve.jsonl`에도 남는다, 덧붙이기 실패에도 종료 코드 유지).

---

## 10. 새 이벤트를 더하는 절차

1. **필요한가.** 같은 사건을 이미 쓰는 이벤트가 있으면 detail 키를 더한다. 지표(Micrometer)로 충분한 수치는 이벤트로 만들지 않는다.
2. **이름과 수준을 정한다.** §2.1 형식, §3 뜻. 이름이 수준을 하나로 정하는지 본다.
3. **detail을 정한다.** §2.2 키 규칙, 공급자 실패면 §4의 위치, §5의 금지 목록을 하나씩 대조한다.
4. **테스트를 먼저 쓴다.** 그 지점에서 이벤트가 이름·수준·소스·run·detail 키와 값으로 남는지 단언한다. 기존 `*EventLogTest`의 모양(로그 캡처 후 엔트리 비교)을 따른다. 테스트 없는 이벤트는 넣지 않는다.
5. **코드를 쓴다.** §7 모양. 호출 지점 하나.
6. **카탈로그(§9)에 행을 더한다.** 같은 커밋이다. 이름을 바꾸거나 지울 때도 같다.
7. **운영 문서를 본다.** `status --log`가 그 수준을 보여 주는지, 운영 절차 문서(`docs/operations/`)에 대응 방법이 필요한지.

자동 검사: `EventCatalogTest`(코드의 `EventLog` 이벤트 집합 = §9 표의 이름 집합, 수준 일치).

---

## 11. 게이트와 고정 목록

`backend/verification`이 `check`에서 돌린다. 범위는 제품 main 코드다(harness 소스셋·`scos-test-support`·testFixtures 제외).

| 게이트 | 무엇을 막나 | 실패 메시지 |
|---|---|---|
| `LoggingRulesTest` | `EventLog` 밖의 SLF4J warn·error 직접 호출·메서드 참조(§6) | 위반한 메서드와 줄 |
| `EventNameRulesTest` | 읽을 수 없는 이름, `영역.대상.동작` 형식이 아닌 이름(§2.1), `EventLog` 멤버의 static import(§7) | `파일:줄 이름`, `파일:줄 EventLog.멤버` |
| `EventCatalogTest` | 코드와 §9 표의 이름·수준 차이(§9) | `카탈로그(§9)에 없다: 이름 (파일:줄)`, `코드에 없다: 이름`, `수준이 다르다: …` |

- 규칙마다 표본 자기 시험이 있다(`LoggingRulesSelfTest`·`scosrules.LoggingSamples`, `EventLogCallsTest`, `EventCatalogTest`의 `Parse`·`Compare`). 규칙을 고치면 표본도 함께 고친다.
- **고정 목록(freeze)**: `LoggingRulesTest`의 기존 위반은 `verification/src/test/resources/frozen/`(ConventionRulesTest와 같은 저장소)에 둔다. 지금은 0건이다. 새 위반은 목록에 넣지 않고 코드에서 `EventLog`로 고친다(`freeze.refreeze=false`).
- **갱신 절차**(고정된 위반을 고쳤을 때): `cd backend && ./gradlew :verification:fastTest --tests 'io.scos.LoggingRules*' -Pscos.freeze.update=true`. 고친 위반이 목록에서 빠진다. 바뀐 목록 파일을 같은 커밋에 넣는다. 새 위반은 이 모드에서도 실패한다. 규칙 설명 문장(`.as(…)`)은 저장소의 열쇠라 바꾸지 않는다. 바꿔야 하면 TESTING §13.2의 재고정 절차를 따른다.
