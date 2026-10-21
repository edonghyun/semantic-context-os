package io.scos.connector.tiro.internal;

import static io.scos.connector.tiro.internal.TiroScenario.JSON;
import static io.scos.connector.tiro.internal.TiroScenario.LIST;
import static io.scos.connector.tiro.internal.TiroScenario.note;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.scos.connector.tiro.internal.FakeTiroServer.Fault;
import io.scos.connector.tiro.internal.TiroScenario.Collection;
import io.scos.connector.tiro.internal.TiroScenario.TsCollection;
import io.scos.engine.support.call.ProviderCallMetrics;
import io.scos.engine.support.call.ProviderCallMetrics.CallOutcome;
import io.scos.engine.support.call.ProviderCallTags;
import io.scos.spi.connector.CallFailure.Kind;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * F8 동등성: 가짜 Tiro 서버(harness/fixtures/tiro/tiro-dataset.json)에서 Java TiroAdapter가 보낸 요청과 만든
 * 페이지·원본·체크포인트가 TS TiroAdapter의 결과(expected-raw-items.json 최상위)와 같다. 재시작·범위 변경·공급자 오류·자원별 접근 제한도
 * 본다.
 */
@DisplayName("Tiro 어댑터는 TS TiroAdapter와 같은 요청·페이지·원본을 낸다(F8)")
class TiroAdapterFixtureTest {
  static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(2);
  static final Duration RETRY_AFTER = Duration.ofSeconds(3);

  /** 재시도 상한 5회 사이의 대기 수. */
  static final int WAITS_BEFORE_GIVING_UP = 4;

  static final int ATTEMPTS = 5;
  static final String ALPHA = "note:n-alpha";

  /** 어댑터의 공급자 호출 이름(지표 operation 태그). */
  static final String READ = "tiro.read";

  final TiroScenario tiro = new TiroScenario();
  final TsCollection ts = new TsCollection();

  @AfterEach
  void tearDown() {
    tiro.close();
  }

  @Nested
  @DisplayName("처음부터 수집하면 TS와 같다")
  class MatchesTs {
    @Test
    @DisplayName("TS와 같은 경로·쿼리를 같은 순서로 보낸다")
    void matchTsRequests() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.requests()).containsExactlyElementsOf(ts.requests());
    }

    @Test
    @DisplayName("키를 Bearer Authorization 헤더로 보낸다")
    void sendsBearerToken() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.authorizations()).isNotEmpty().containsOnly(tiro.bearerToken());
    }

    @Test
    @DisplayName("JSON 응답을 요청한다")
    void acceptsJson() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.accepts()).isNotEmpty().containsOnly("application/json");
    }

    @Test
    @DisplayName("키를 쿼리스트링에 싣지 않는다")
    void keepsTokenOutOfQuery() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.requests()).isNotEmpty().noneMatch(r -> r.contains(tiro.datasetToken()));
    }

    @Test
    @DisplayName("목록 페이지마다 한 페이지를 낸다")
    void pages() {
      // when
      Collection java = tiro.collect();

      // then
      assertThat(java.pages()).hasSize(ts.pagesApplied());
    }

    @Test
    @DisplayName("노트 원본의 필드가 같다(버전·시각·메타데이터·본문)")
    void items() {
      // when
      Collection java = tiro.collect();

      // then: TS 기대 항목은 저장소 목록 순(externalId 순)이고, 페이지 안의 upsert 순서는 목록 순이다 — 순서는 따로 본다
      assertThat(java.upserts()).containsExactlyInAnyOrderElementsOf(ts.items());
    }

    @Test
    @DisplayName("노트 원본은 목록 순서로 낸다")
    void upsertsFollowListOrder() {
      // when
      Collection java = tiro.collect();

      // then
      assertThat(java.upserts())
          .extracting(TiroScenario.ItemView::externalId)
          .containsExactly("note:n-alpha", "note:n-beta", "note:n-gamma", "note:n-delta");
    }

    @Test
    @DisplayName("상세가 403·404인 노트는 삭제로 낸다")
    void unreadableNotesAreDeleted() {
      // when
      Collection java = tiro.collect();

      // then
      assertThat(java.deletes()).containsExactly("note:n-locked", "note:n-gone");
    }

    @Test
    @DisplayName("잠긴 문단이 있어도 거부 항목은 없다")
    void lockedParagraphsAreNotRejections() {
      // when
      Collection java = tiro.collect();

      // then: TS 보고의 rejected도 0이다
      assertThat(java.rejections()).isEmpty();
    }

    @Test
    @DisplayName("최종 체크포인트(범위와 커서 없음)가 같다")
    void finalCheckpoint() {
      // when
      Checkpoint last = tiro.collect().finalCheckpoint();

      // then
      assertThat(last)
          .extracting(Checkpoint::schemaVersion, c -> JSON.readTree(c.valueJson()))
          .containsExactly(
              ts.checkpoint().get("schemaVersion").asInt(), ts.checkpoint().get("value"));
    }

    @Test
    @DisplayName("첫 페이지 체크포인트는 다음 목록 커서를 기록한다")
    void firstCheckpointRecordsCursor() {
      // when
      Checkpoint first = tiro.collect().firstCheckpoint();

      // then
      assertThat(JSON.readTree(first.valueJson()).get("cursor").asString()).isEqualTo("page:1");
    }

    @Test
    @DisplayName("재시도 대기는 없다")
    void noRetries() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.waits()).isEmpty();
    }
  }

  @Nested
  @DisplayName("allNotes 설정으로 수집하면 TS와 같다")
  class AllNotes {
    @Test
    @DisplayName("같은 경로·쿼리를 같은 순서로 보낸다")
    void matchTsRequests() {
      // when
      tiro.collectWithConfig(ts.allNotesConfig());

      // then
      assertThat(tiro.requests()).containsExactlyElementsOf(ts.allNotesRequests());
    }

    @Test
    @DisplayName("목록 요청에 폴더를 싣지 않는다")
    void allNotesListsWithoutFolder() {
      // when
      tiro.collectWithConfig("{\"workspaceGuid\":\"ws-synthetic\",\"allNotes\":true}");

      // then
      assertThat(tiro.requests().getFirst()).isEqualTo(LIST + "?size=20");
    }

    @Test
    @DisplayName("최종 체크포인트(allNotes 범위)가 같다")
    void finalCheckpoint() {
      // when
      Checkpoint last = tiro.collectWithConfig(ts.allNotesConfig()).finalCheckpoint();

      // then
      assertThat(last)
          .extracting(Checkpoint::schemaVersion, c -> JSON.readTree(c.valueJson()))
          .containsExactly(
              ts.allNotesCheckpoint().get("schemaVersion").asInt(),
              ts.allNotesCheckpoint().get("value"));
    }
  }

  @Nested
  @DisplayName("중간 체크포인트에서 다시 수집하면")
  class FromMidCheckpoint {
    Checkpoint mid;
    int requestsBefore;

    @BeforeEach
    void givenFirstCollection() {
      mid = tiro.givenCollected().firstCheckpoint();
      requestsBefore = tiro.requests().size();
    }

    @Test
    @DisplayName("목록 첫 페이지부터 다시 걷는다")
    void restartsFromFirstPage() {
      // when
      tiro.collectFrom(mid);

      // then: 기대 요청은 TS 수집의 첫 요청(fixture)이다
      assertThat(tiro.requests().get(requestsBefore)).isEqualTo(ts.requests().getFirst());
    }

    @Test
    @DisplayName("모든 페이지를 낸다")
    void restartYieldsEveryPage() {
      // when
      Collection again = tiro.collectFrom(mid);

      // then
      assertThat(again.pages()).hasSize(ts.pagesApplied());
    }
  }

  /** 키 순서가 달라도 scope는 TS JSON.stringify(configuration) 순서다. */
  @Nested
  @DisplayName("설정 키 순서가 다른 설정으로 수집하면")
  class ReorderedConfig {
    @Test
    @DisplayName("체크포인트 scope는 설정 키 순서와 무관하다")
    void scopeIgnoresConfigKeyOrder() {
      // when
      Checkpoint reordered =
          tiro.collectWithConfig("{\"folderId\":\"folder-1\",\"workspaceGuid\":\"ws-synthetic\"}")
              .finalCheckpoint();

      // then
      assertThat(JSON.readTree(reordered.valueJson())).isEqualTo(ts.checkpoint().get("value"));
    }
  }

  @Nested
  @DisplayName("coverage를 물으면")
  class Coverage {
    @ParameterizedTest(name = "{0}")
    @EnumSource(CollectionMode.class)
    @DisplayName("모드와 무관하게 complete_snapshot이다")
    void alwaysCompleteSnapshot(CollectionMode mode) {
      // when
      CollectionCoverage coverage = tiro.coverage(mode);

      // then
      assertThat(coverage).isEqualTo(CollectionCoverage.COMPLETE_SNAPSHOT);
    }
  }

  @Nested
  @DisplayName("수집 범위가 바뀐 저장 체크포인트에서 수집하면")
  class ChangedScopeCheckpoint {
    static final Checkpoint OTHER_SCOPE =
        new Checkpoint(
            1,
            "{\"scope\":\"{\\\"workspaceGuid\\\":\\\"ws-synthetic\\\",\\\"allNotes\\\":true}\",\"cursor\":null}");

    @Test
    @DisplayName("invalid_checkpoint로 실패한다")
    void changedScopeIsRejected() {
      // when
      Collection collection = tiro.collectFrom(OTHER_SCOPE);

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_CHECKPOINT);
    }

    @Test
    @DisplayName("요청을 보내지 않는다")
    void changedScopeSendsNothing() {
      // when
      tiro.collectFrom(OTHER_SCOPE);

      // then
      assertThat(tiro.requests()).isEmpty();
    }
  }

  @Nested
  @DisplayName("같은 범위의 저장 체크포인트를 읽으면")
  class StoredCheckpointShape {
    static final String SCOPE =
        "\"{\\\"workspaceGuid\\\":\\\"ws-synthetic\\\",\\\"folderId\\\":\\\"folder-1\\\"}\"";

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "[]",
          "{\"cursor\":null}",
          "{\"scope\":7,\"cursor\":null}",
          "{\"scope\":" + SCOPE + "}",
          "{\"scope\":" + SCOPE + ",\"cursor\":\"\"}",
          "{\"scope\":" + SCOPE + ",\"cursor\":3}"
        })
    @DisplayName("형식이 틀린 저장 체크포인트는 invalid_checkpoint다")
    void malformedCheckpointIsRejected(String value) {
      // when
      Collection collection = tiro.collectFrom(new Checkpoint(1, value));

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_CHECKPOINT);
    }

    @Test
    @DisplayName("스키마 버전이 1이 아니면 invalid_checkpoint다")
    void otherSchemaVersionIsRejected() {
      // when
      Collection collection =
          tiro.collectFrom(new Checkpoint(2, "{\"scope\":" + SCOPE + ",\"cursor\":null}"));

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_CHECKPOINT);
    }

    @Test
    @DisplayName("진행 중 커서는 받아들인다")
    void sameScopeWithCursorIsAccepted() {
      // when
      Collection collection =
          tiro.collectFrom(new Checkpoint(1, "{\"scope\":" + SCOPE + ",\"cursor\":\"page:1\"}"));

      // then
      assertThat(collection.failureKind()).isEmpty();
    }
  }

  @Nested
  @DisplayName("키가 틀려 401이면")
  class Unauthorized {
    @BeforeEach
    void givenWrongToken() {
      tiro.withWrongToken();
    }

    @Test
    @DisplayName("authentication_failed로 끝난다")
    void unauthorizedFails() {
      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("재시도하지 않는다")
    void unauthorizedIsNotRetried() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.requests()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("목록이 403·404면")
  class ListForbidden {
    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {403, 404})
    @DisplayName("permission_denied로 실패한다")
    void listForbiddenFails(int status) {
      // given
      tiro.failNext(LIST, 1, Fault.status(status));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.PERMISSION_DENIED);
    }
  }

  @Nested
  @DisplayName("목록 첫 요청이 429(Retry-After 3)면")
  class ListRateLimitedOnce {
    @BeforeEach
    void givenListRateLimitedOnce() {
      tiro.failNext(LIST, 1, Fault.rateLimited("3"));
    }

    @Test
    @DisplayName("Retry-After만큼 기다려 재시도한다")
    void rateLimitedWaitsRetryAfter() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.waits()).containsExactly(RETRY_AFTER);
    }

    @Test
    @DisplayName("재시도가 성공하면 수집이 끝난다")
    void rateLimitedThenCompletes() {
      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.pages()).hasSize(ts.pagesApplied());
    }

    @Test
    @DisplayName("재시도는 tiro.read 재시도 지표로 남는다")
    void rateLimitedRetryIsCounted() {
      // when
      tiro.collect();

      // then
      assertThatMetrics(tiro.metrics())
          .hasCounter(
              ProviderCallMetrics.PROVIDER_CALLS,
              ProviderCallTags.of(READ, CallOutcome.RETRY, Optional.of(Kind.RATE_LIMITED)),
              1);
    }
  }

  @Nested
  @DisplayName("노트 요약 요청이 한 번 429면")
  class NoteRateLimitedOnce {
    @Test
    @DisplayName("접근 제한이 아니라 재시도해 요약을 받는다")
    void rateLimitedNoteResourceIsRetried() {
      // given
      tiro.failNext(note("n-alpha", "summaries"), 1, Fault.rateLimited("3"));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).value().get("summaries")).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("노트 문서 요청이 시도 상한까지 503이면")
  class DocumentsUnavailable {
    @BeforeEach
    void givenDocumentsUnavailable() {
      tiro.failNext(note("n-alpha", "documents"), ATTEMPTS, Fault.status(503));
    }

    @Test
    @DisplayName("tiro.read 실패 지표로 남는다")
    void exhaustedRetriesAreCountedAsFailure() {
      // when
      tiro.collect();

      // then
      assertThatMetrics(tiro.metrics())
          .hasCounter(
              ProviderCallMetrics.PROVIDER_CALLS,
              ProviderCallTags.of(
                  READ, CallOutcome.FAILED, Optional.of(Kind.DEPENDENCY_UNAVAILABLE)),
              1);
    }

    @Test
    @DisplayName("dependency_unavailable로 실패한다")
    void serverErrorsExhaustRetries() {
      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    @DisplayName("재시도 상한까지 기다린다")
    void serverErrorsWaitUntilRetryLimit() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.waits()).hasSize(WAITS_BEFORE_GIVING_UP);
    }
  }

  @Nested
  @DisplayName("노트 요약 요청이 시도 상한까지 503이면")
  class SummariesUnavailable {
    @Test
    @DisplayName("그 노트의 페이지를 내지 않는다")
    void failedChildCommitsNothing() {
      // given
      tiro.failNext(note("n-alpha", "summaries"), ATTEMPTS, Fault.status(503));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.pages()).isEmpty();
    }
  }

  @Nested
  @DisplayName("목록 첫 요청이 400이면")
  class ListBadRequest {
    @BeforeEach
    void givenListBadRequest() {
      tiro.failNext(LIST, 1, Fault.status(400));
    }

    @Test
    @DisplayName("invalid_provider_response로 실패한다")
    void badRequestFails() {
      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.failureKind()).contains(Kind.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    @DisplayName("재시도하지 않는다")
    void badRequestIsNotRetried() {
      // when
      tiro.collect();

      // then
      assertThat(tiro.requests()).hasSize(1);
    }

    @Test
    @DisplayName("실패 메시지에 응답 본문과 키를 싣지 않는다")
    void failureHidesBodyAndKey() {
      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.failureMessage()).hasValue("Tiro request failed (HTTP 400)");
    }
  }

  @Nested
  @DisplayName("목록 첫 응답이 JSON 아닌 2xx나 3xx면")
  class ListOddSuccess {
    @Test
    @DisplayName("2xx 본문이 JSON이 아니면 재시도한다")
    void nonJsonBodyIsRetried() {
      // given
      tiro.failNext(LIST, 1, Fault.nonJson(200));

      // when
      tiro.collect();

      // then
      assertThat(tiro.waits()).containsExactly(FIRST_RETRY_DELAY);
    }

    @Test
    @DisplayName("3xx 응답은 재시도한다")
    void redirectIsRetried() {
      // given
      tiro.failNext(LIST, 1, Fault.status(302));

      // when
      tiro.collect();

      // then
      assertThat(tiro.waits()).containsExactly(FIRST_RETRY_DELAY);
    }
  }

  @Nested
  @DisplayName("노트 아래 자원 한 종류만 403이면")
  class ResourceForbidden {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"paragraphs", "summaries", "documents"})
    @DisplayName("메타데이터에 그 종류를 접근 제한으로 남긴다")
    void recordsUnavailableResource(String resource) {
      // given
      tiro.failNext(note("n-alpha", resource), 1, Fault.status(403));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).metadata().get("unavailableResources"))
          .isEqualTo(JSON.readTree("[\"" + resource + "\"]"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"paragraphs", "summaries", "documents"})
    @DisplayName("본문에서 그 종류를 비운다")
    void emptiesForbiddenResource(String resource) {
      // given
      tiro.failNext(note("n-alpha", resource), 1, Fault.status(403));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).value().get(resource)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"paragraphs", "summaries", "documents"})
    @DisplayName("다른 종류의 본문은 TS 원본 그대로 남긴다")
    void keepsOtherResources(String resource) {
      // given
      tiro.failNext(note("n-alpha", resource), 1, Fault.status(403));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(without(collection.upsert(ALPHA).value(), resource))
          .isEqualTo(without(ts.item(ALPHA).value(), resource));
    }

    @Test
    @DisplayName("둘째 문단 페이지가 404여도 문단 전체를 비운다")
    void laterParagraphPageEmptiesAll() {
      // given
      tiro.failOnCursor(note("n-alpha", "paragraphs"), "page:1", 1, Fault.status(404));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).value().get("paragraphs")).isEmpty();
    }

    @Test
    @DisplayName("요약 상세 하나가 404여도 요약 전체를 접근 제한으로 남긴다")
    void summaryDetailNotFoundEmptiesSummaries() {
      // given
      tiro.failNext(note("n-alpha", "summaries", "7"), 1, Fault.status(404));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).metadata().get("unavailableResources"))
          .isEqualTo(JSON.readTree("[\"summaries\"]"));
    }

    @Test
    @DisplayName("노트 상세가 403이면 그 노트를 삭제로 낸다")
    void forbiddenNoteIsDeleted() {
      // given
      tiro.failNext(note("n-alpha"), 1, Fault.status(403));

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.deletes()).containsExactly(ALPHA, "note:n-locked", "note:n-gone");
    }
  }

  @Nested
  @DisplayName("노트 자원마다 수정 시각이 다르면")
  class SourceUpdatedAt {
    static final String DOCUMENT = note("n-alpha", "documents", "11");
    static final String SUMMARY = note("n-alpha", "summaries", "7");
    static final String SECTIONS = "\"sections\":[{\"content\":{\"content\":\"본문\"}}]";

    @Test
    @DisplayName("회의 문서가 가장 늦게 바뀌었으면 sourceUpdatedAt은 그 시각이다")
    void usesLatestDocument() {
      // given
      tiro.answer(
          DOCUMENT,
          "{\"id\":11,\"noteGuid\":\"n-alpha\","
              + SECTIONS
              + ",\"updatedAt\":\"2026-10-09T00:00:00Z\"}");

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).sourceUpdatedAt()).isEqualTo("2026-10-09T00:00:00Z");
    }

    @Test
    @DisplayName("같은 시각이면 먼저 본 자원(노트)의 표기를 쓴다")
    void keepsFirstOnTie() {
      // given
      tiro.answer(
          SUMMARY,
          "{\"id\":7,\"noteGuid\":\"n-alpha\",\"content\":{\"content\":\"요약\"},"
              + "\"updatedAt\":\"2026-10-01T11:00:00+09:00\"}");
      tiro.answer(DOCUMENT, "{\"id\":11,\"noteGuid\":\"n-alpha\"," + SECTIONS + "}");

      // when
      Collection collection = tiro.collect();

      // then
      assertThat(collection.upsert(ALPHA).sourceUpdatedAt()).isEqualTo("2026-10-01T02:00:00Z");
    }
  }

  @Nested
  @DisplayName("수집 전에 설정을 검증하면")
  class ValidateConfig {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "{\"workspaceGuid\":\"ws\",\"folderId\":\"f\"}",
          "{\"folderId\":\"f\",\"workspaceGuid\":\"ws\"}",
          "{\"workspaceGuid\":\"ws_1-A\",\"allNotes\":true}"
        })
    @DisplayName("워크스페이스와 선택 하나면 통과한다")
    void validConfigurations(String config) {
      // when
      Optional<Kind> problem = tiro.validate(1, config);

      // then
      assertThat(problem).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "[]",
          "{}",
          "{\"workspaceGuid\":\"ws\"}",
          "{\"folderId\":\"f\"}",
          "{\"workspaceGuid\":\"ws\",\"folderId\":\"f\",\"allNotes\":true}",
          "{\"workspaceGuid\":\"ws\",\"allNotes\":false}",
          "{\"workspaceGuid\":\"ws\",\"allNotes\":\"true\"}",
          "{\"workspaceGuid\":\"ws\",\"folderId\":null}",
          "{\"workspaceGuid\":\"ws\",\"folderId\":\"a/b\"}",
          "{\"workspaceGuid\":\"\",\"folderId\":\"f\"}",
          "{\"workspaceGuid\":7,\"folderId\":\"f\"}",
          "{\"workspaceGuid\":\"ws\",\"allNotes\":true,\"folder\":\"f\"}"
        })
    @DisplayName("선택이 모호하거나 틀린 설정은 invalid_configuration이다")
    void invalidConfigurations(String config) {
      // when
      Optional<Kind> problem = tiro.validate(1, config);

      // then
      assertThat(problem).contains(Kind.INVALID_CONFIGURATION);
    }

    @Test
    @DisplayName("스키마 버전이 1이 아니면 invalid_configuration이다")
    void otherSchemaVersionIsInvalid() {
      // when
      Optional<Kind> problem = tiro.validate(2, "{\"workspaceGuid\":\"ws\",\"folderId\":\"f\"}");

      // then
      assertThat(problem).contains(Kind.INVALID_CONFIGURATION);
    }
  }

  @Nested
  @DisplayName("틀린 설정으로 수집하면")
  class InvalidConfig {
    @Test
    @DisplayName("요청을 보내지 않는다")
    void invalidConfigurationSendsNothing() {
      // when
      tiro.collectWithConfig("{\"workspaceGuid\":\"ws\"}");

      // then
      assertThat(tiro.requests()).isEmpty();
    }
  }

  /** 비교에서 뺄 자원 종류와 접근 제한 목록을 지운 사본. */
  static JsonNode without(JsonNode value, String resource) {
    ObjectNode copy = (ObjectNode) value.deepCopy();
    copy.remove(List.of(resource, "unavailableResources"));
    return copy;
  }
}
