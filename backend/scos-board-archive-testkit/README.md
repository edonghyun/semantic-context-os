# scos-board-archive-testkit

소비 앱의 `BoardDriver`를 프레임워크와 같은 사례로 검증하는 main JAR다. 소비 앱의 **테스트 의존**으로만 사용한다.

```kotlin
dependencies {
    testImplementation("io.scos:scos-board-archive-testkit:<version>")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
```

JUnit Jupiter API·params, AssertJ와 `scos-board-archive`는 API 의존으로 전해진다. 실행 엔진과 launcher는 소비 앱이 지정한다. testFixtures 변형을 선택할 필요가 없다.

## 계약 선택

쪽 넘김은 `PagedBoardDriverContract` 또는 `SinglePageBoardDriverContract`를 상속한다. 두 클래스 모두 `BoardDriverContract`를 포함한다. 기본 계약만 직접 상속할 수도 있다.

독립된 선택 계약은 JUnit 테스트 인터페이스다. 반드시 위 기본 계약을 상속하는 클래스에 `implements`로 조합하고, 상속한 테스트를 재정의하지 않는다. 기본 계약의 준비 단계는 선택 계약 사례의 재정의를 검사하며, `@Test` 유무와 무관하게 `given 준비 실패: 선택 계약 사례 …를 재정의했다`로 멈춘다. 기본 계약 없이 선택 인터페이스만 쓰면 `given 준비 실패: 선택 계약은 BoardDriverContract 하위 클래스에서 구현한다`로 원인을 알린다. 쪽 넘김·분류·부속 요청의 조합마다 새 상위 클래스를 만들 필요가 없다.

| 선택 계약 | 선택할 조건 | 추가 사례(실행 건수) |
|---|---|---|
| `StaticBoardsContract` | 게시판 목록을 HTTP 없이 제공 | Fetcher 전송 실패·robots 거부 중에도 id·이름·순서를 반환(2) |
| `RemoteBoardsContract` | 게시판 메뉴를 원격 조회 | 같은 Fetcher 예외 전파(2), 메뉴 HTTP 500의 상태 예외(1) |
| `CategorizedBoardsContract` | 고정 자료의 분류를 전달 | 분류 `Public`·`Archive` 순서(1) |
| `CompanionRequestsContract` | 본문 외 부속 요청이 있음 | 첫 부속 경로가 500일 때 Found의 URL·제목 보존과 불완전 댓글(1) |
| `ArchivedPostContract` | `fetchArchivedPost`가 보관본 URL을 관측한 URL로 취급 | 목록을 읽지 않은 새 드라이버에서 보관본 URL 404→Deleted, 500→Unknown, 200→그 URL의 Found, 보관본 URL과 부속 경로만 순서대로 각 1회 요청(4) |

게시판 조회 방식에 맞춰 Static/Remote 중 정확히 하나를 고른다. 선택 누락과 중복은 드라이버 생성 전 `given 준비 실패: …`로 멈춘다. 분류 없는 드라이버는 Categorized를 선택하지 않는다. 부속 요청이 없으면 Companion을 선택하지 않는다. 기본 계약의 요청 검증은 선택 여부와 무관하게 선언된 모든 경로의 순서·각 1회를 검증한다.

## 훅과 예

모든 공개 타입은 `io.scos.boardarchive.testkit` 패키지다. 필수 훅 네 개, 기본 구현이 있는 상태 주입 훅 두 개, 선택적인 경로 훅이 있다.

```java
@DisplayName("설정형 드라이버는 게시판 계약을 지킨다 (BoardDriver)")
class MyDriverContractTest extends PagedBoardDriverContract
    implements StaticBoardsContract, CategorizedBoardsContract {
  @Override
  protected BoardDriver createDriver(LocalSite site, MutableClock clock) {
    // 아래 고정 자료를 자기 형식으로 site에 등록하고 새 드라이버를 반환한다.
    // 저장 커서 사례에서 같은 site와 clock으로 다시 호출된다.
    return fixtureDriver(site, clock);
  }

  @Override
  protected String postId(String board, int number) {
    return board + "-" + number;
  }

  @Override
  protected String listPath(String board) {
    return "/list/" + board + "?offset=0";
  }

  @Override
  protected String postPath(String board, int number) {
    return "/posts/" + board + "-" + number;
  }
}
```

`givenListStatus(status)`의 기본 구현은 `listPath("a")`에, `givenPostStatus(status)`는 `postPath("a", 12)`에 그 상태의 HTTP 응답을 등록한다. 응답 상태를 자기 상태로 두는 스크립트 대역만 이 둘을 재정의한다.

원격 메뉴를 읽으면 `RemoteBoardsContract`를 구현하고 `public String boardsPath()`로 메뉴 상대 경로(예: `/boards`)를 사상한다. `createDriver`에서 메뉴의 200 응답도 등록한다. 시나리오는 드라이버 생성 직후 게시판을 조회해 실제 반환한 `Board` 메타데이터를 목록·본문에 전달한다. 실패 사례의 예외/응답은 이 정상 준비 뒤에 주입된다.

본문 외 요청을 하면 다음 훅을 재정의하고 `CompanionRequestsContract`를 구현한다. 훅의 기본값은 빈 목록이다.

```java
@Override
protected List<String> companionPaths(String board, int number) {
  return List.of("/comments/" + postId(board, number) + "?page=1");
}
```

`createDriver`에서 a/12와 b/12의 부속 경로 200 응답도 자기 형식으로 등록한다. 요청은 `[postPath(board, number)] + companionPaths(board, number)` 순서로 **각 1회**여야 한다. 미선언 경로와 중복 요청은 실패한다. 기본 계약의 준비 단계는 `companionPaths("a", 12)`의 비어 있지 않음과 Companion 선택이 일치하는지 확인한다. 부속 선언 뒤 Companion을 빠뜨리거나 빈 선언으로 Companion을 선택하면 드라이버 생성 전 `given 준비 실패: …`로 멈춘다. 경로 사상 훅은 드라이버 생성 전에도 호출되므로 시나리오 상태에 의존하지 않는 이름 사상이어야 한다.

이름 사상 훅은 id·wire 경로만 바꾼다(query 포함, 같은 origin의 상대 경로). `postId`는 사이트 안에서 유일해야 한다. 숫자 ID라면 a/12→`112`, b/12→`212`처럼 사상할 수 있다. 기대 제목·시각·순서·판정·예외·분류는 훅으로 바꾸지 않는다. `givenListStatus`·`givenPostStatus`는 상태 주입만 수행하고 목록을 읽지 않는다. 스크립트 대역은 이 훅에서 자기 응답 상태를 설정할 수 있다. 테스트 컨텍스트는 외부 소비자에게 노출되지 않는다.

완전한 예: 프레임워크의 `ProfileDriverHtmlContractTest`·`ProfileDriverJsonContractTest`·`ScriptedDriverContractTest`, 키트의 `NumericSinglePageContractTest`·`RemoteBoardsContractTest`. 소비자 둘의 공통 이름 사상은 `NumericBoardDriverContract`에 모았다. 원격 소비자는 빈 분류·목록 관측 필요·별도 댓글 요청을 함께 검증한다.

## 고정 자료

게시판 id·이름·순서는 `a / General`, `b / Other`다. 분류를 선택하면 각각 `Public`, `Archive`를 등록한다. 기본 계약은 분류를 제한하지 않는다. 아래는 기본 wire 경로의 예다. 시각은 한국 시각 자정이라 일 단위 파서도 사용할 수 있다.

| 게시판 | 논리 번호 | 제목 | 작성 시각 (UTC) | 고정 | 위치 / 링크 |
|---|---:|---|---|---|---|
| a | 14 | Notice | 2026-11-14T15:00:00Z | true | 첫 쪽 1번째, `/posts/a-14` |
| a | 12 | First | 2026-11-16T15:00:00Z | false | 첫 쪽 2번째, `/posts/a-12` |
| a | 11 | Second | 2026-11-15T15:00:00Z | false | 첫 쪽 3번째, `/posts/a-11` |
| a | 10 | Third | 2026-11-13T15:00:00Z | false | 다음 쪽, `/posts/a-10` (Paged만) |
| b | 12 | Other first | 2026-11-16T15:00:00Z | false | 첫 쪽, `/posts/b-12` |

첫 목록 경로는 `listPath("a")`·`listPath("b")`다. Paged의 첫 쪽은 불투명 다음 커서를 반환하고 다음 쪽에는 a/10만 있으며 마지막 커서는 비어야 한다. 다음 쪽 경로·커서 인코딩은 구현이 정한다. SinglePage는 첫 쪽의 빈 다음 커서를 요구하고 a/10은 요구하지 않는다. 모든 요약의 signal은 비어 있다.

본문 200 응답은 a/12의 제목 `First`, b/12의 제목 `Other first`, 각각의 `postPath` 원문 URL, `2026-11-16T15:00:00Z`, 빈 댓글·`commentsComplete=true`를 만들어야 한다. 본문과 부속 응답을 모두 등록한다. 본문 404→Deleted, 403→AccessDenied, 302·500→Unknown이다. 목록 302·403·404·500은 상태가 있는 HttpStatusException이다. Fetcher 전송 실패·robots 거부는 같은 예외 객체로 전파한다.

모든 본문 사례는 **시나리오가 같은 드라이버로 대상이 있는 첫 쪽을 읽고 id를 확인한 뒤** 실행한다. 실패 응답·예외도 관측 뒤에 주입한다. `requestsDuring`은 fetch만 감싸므로 관측·게시판 조회 요청은 본문 요청 수에 섞이지 않는다. 미관측 URL의 404 판정은 키트의 공통 계약 밖이다. `CrawlRunner`의 pending 본문과 `SweepPass`의 누락 글 확인은 현재 실행에서 관측되지 않은 글을 요청할 수 있다. `SweepPass`는 본문을 보관한 누락 글을 `fetchArchivedPost(http, board, postId, archivedUrl)`로 확인한다. `archivedUrl`은 최근 revision과 함께 저장된 `Found.url`(`post_revision.url`)이다. 드라이버의 `Found.url`이 실제로 요청한 URL일 때만 그 404를 삭제 근거로 쓴다. 목록에서 관측한 URL만 404 판정에 쓰는 드라이버는 이 메서드를 구현해 그 URL의 404를 Deleted로 판정해야 누락 글이 투영에서 빠진다. 수집기는 서로 다른 두 훑기에서, 24시간 이상 간격을 두고 Deleted를 받아야 삭제로 확정한다. 구현하지 않으면 기본 구현이 `fetchPost`를 불러 Unknown이 반복되고, NEEDS_CHECK로 투영에 남는다. 5xx 같은 일시 오류는 이 메서드에서도 Unknown이다. 이 메서드를 구현했다면 `ArchivedPostContract`를 고른다. 보관본 URL은 `postPath("a", 12)`의 원문 URL이고, 상태는 `givenPostStatus`로 주입한다. 다른 드라이버를 감싸는 데코레이터는 이 메서드도 위임해야 한다. 컴파일러는 위임 누락을 잡지 못한다. 감싼 드라이버의 `fetchPost`가 미관측 글의 404를 Deleted로 보지 않는다면, 데코레이터의 계약 테스트도 `ArchivedPostContract`를 고를 때 누락이 실패로 드러난다. 보관본 URL이 `postPath("a", 12)`와 같으므로, 템플릿 경로로 다시 만든 URL의 404를 Deleted로 보는 드라이버는 위임 없이도 통과한다.

## 사례 표

기본 계약 22건, Paged 추가 4건, SinglePage 추가 1건이다. 선택 계약 수는 위 표와 같다. 파라미터 사례는 입력마다 한 건이다.

| 계약 | 사례 메서드 | 실행 건수 |
|---|---|---:|
| 기본 게시판 | `returnsConfiguredBoards` | 1 |
| 첫 쪽 요약·id | `firstPageHasPostSummaries`, `firstPagePostIdsAreUnique`, `postIdsAreUniqueWithinSite` | 3 |
| 목록 실패 | `listFailureIncludesStatus`(302/403/404/500), `listingPropagatesFetcherFailure`(2) | 6 |
| 관측 뒤 본문 | `successfulPostIsFound`, `secondBoardPostUsesItsOwnPath`, `noCommentsIsCompleteEmptyList` | 3 |
| 관측 뒤 본문 판정 | `missingPostIsDeleted`, `forbiddenPostIsAccessDenied`, `otherPostFailureIsUnknown`(302/500) | 4 |
| 관측 뒤 본문 예외 | `fetchingPropagatesFetcherFailure`(2) | 2 |
| 요청 경로·횟수 | `listingMakesOneRequest`, `fetchingUsesDeclaredRequests`(a/b) | 3 |
| Paged | `firstPageHasContinuation`, `cursorReadsNextPosts`, `savedCursorWorksWithNewDriver`, `lastPageHasNoContinuation` | 4 |
| SinglePage | `firstPageHasNoContinuation` | 1 |

v2에서 바뀐 사례: `returnsConfiguredBoards`는 분류를 별도 계약으로 옮겼다. `boardsDoNotSwallowFetcherFailure`는 Static/Remote의 명시적 사례로 교체했다. `fetchingMakesOneRequest`는 `fetchingUsesDeclaredRequests`로 바뀌고 부속 선언을 검증한다. 모든 본문 사례에 관측 준비가 추가됐다. b/12 본문·원격 메뉴 500·선택 분류·부속 500 사례가 추가됐다. 기존 성질을 지키는 사례는 삭제하지 않았다.

## 기존 소비자 옮기는 법

기존 여섯 훅의 시그니처는 유지된다. `givenListStatus`·`givenPostStatus`가 위 기본 구현과 같다면 재정의를 지워도 된다. 아래 변경으로 테스트 선택과 고정 자료를 갱신한다.

1. 정적 게시판이면 `implements StaticBoardsContract`, 원격이면 `implements RemoteBoardsContract`와 `public boardsPath()`를 추가한다. 기존 기본 클래스의 게시판 예외 사례는 이 선택 계약으로 이동했다. 선택을 빠뜨리거나 둘 다 구현하면 준비 실패다.
2. `Public`·`Archive` 분류 전달을 검증하려면 `CategorizedBoardsContract`를 추가한다. 빈 분류는 선택 없이 기본 계약으로 검증한다.
3. `createDriver`에 **b/12의 200 본문**을 추가한다. a/12를 복제하면 제목/URL 검증이 실패한다.
4. 본문 외 요청은 `companionPaths`를 사상하고 모든 부속 200 응답을 등록한 뒤 `CompanionRequestsContract`를 추가한다.
5. 선택 인터페이스의 테스트를 재정의했다면 제거한다. `@Test`가 없는 재정의도 준비 실패다. 기대값은 키트가 소유하며 이름 사상과 계약 선택만 소비자가 정한다.
6. 상태 주입/생성 훅에서 목록을 미리 읽던 코드를 제거한다. 관측은 시나리오가 맡으며 측정은 fetch 요청만 포함한다.

이전 단일 본문 요청 가정과 게시판 예외 테스트 이름을 직접 참조한 필터도 새 이름으로 갱신한다. 제품 `BoardDriver` API는 바뀌지 않는다.

## 로컬 서버와 게시

`LocalSite`는 loopback 임시 포트만 쓴다. `uri`, `respond`, `enqueue`, `requests`, `requestsDuring`, `close`로 고정 응답과 기록을 관리한다. 미등록 경로는 500으로 실패한다. 시나리오의 Fetcher는 다른 origin을 거부하고 redirect를 따르지 않는다. 시나리오가 HTTP 클라이언트와 사이트를 닫는다. `MutableClock`은 `2026-11-18T00:00:00Z`에서 시작하고 `advance(Duration)`로 진행한다.

저장소 루트, JDK 21에서 `$repository`는 `mktemp -d`로 만든 임시 경로, `$version`은 소비 버전으로 설정한다.

```bash
backend/gradlew -p backend --no-configuration-cache \
  -I "$PWD/tools/local-runtime.init.gradle" \
  "-Pscos.localVersion=$version" "-Pscos.localRepository=$repository" \
  :scos-spi:publishLocalRuntimePublicationToLocalRuntimeRepository \
  :scos-board-archive:publishLocalRuntimePublicationToLocalRuntimeRepository \
  :scos-board-archive-testkit:publishLocalRuntimePublicationToLocalRuntimeRepository
```

게시물은 모듈별 main JAR·POM·Gradle metadata이며 testFixtures 변형·소비자 테스트·HTML/JSON 고정 자료는 제외한다. 계약·선택 인터페이스·LocalSite·MutableClock은 testkit의 의도된 main API다. 제품 main의 testkit 의존은 `checkModuleGraph`가 거부한다.
