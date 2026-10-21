package io.scos.connector.tiro.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.CheckpointPort;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.RawItemStore;
import io.scos.collection.internal.RawRecord;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.StoredCheckpoint;
import io.scos.collection.internal.TsRawViews.ReportView;
import io.scos.connector.tiro.TiroAdapter;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tiro 수집을 실제 런타임(점유·페이지 커밋·완료 정리)으로 돌린 결과가 TS와 같다 — 메모리 포트(TESTING §3.6 5층, {@code
 * MemoryTiroCollectionTest})와 PostgreSQL(6층, {@code TiroCollectionTest})이 같은 사례를 지킨다(§5 계약 형태). 첫
 * 수집과, 목록에서 노트 하나가 빠진 뒤의 두 번째 수집(complete_snapshot 정리)을 TS 기대(expected-raw-items.json)와 비교하고, 목록이
 * 중간에 실패하면 정리하지 않는다는 것, 목록 형식이 틀리거나 수집 범위가 바뀌어 run이 실패하면 원본·tombstone·체크포인트가 그대로라는 것을 본다. 구현 차이는
 * {@link #newRuntime}에만 있다.
 */
@DisplayName("Tiro 수집은 실제 런타임에서 TS와 같이 남기고 정리한다(§3.6 5·6층)")
public abstract class TiroCollectionContract {
  static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  static final String REMOVED = "n-gamma";
  static final String LIST = "/v1/external/workspaces/ws-synthetic/notes";
  static final String SECOND_PAGE = "page:1";
  static final int ATTEMPTS = 5;

  /** 구현별 저장소 위의 실제 수집 런타임과 그 저장소(소스 등록·원본·체크포인트 관찰용). */
  public record Assembled(
      CollectionRuntime runtime,
      SourceInstanceStore sources,
      RawItemStore rawItems,
      CheckpointPort checkpoints) {}

  /** 가짜 Tiro를 가리키는 어댑터로 이 구현의 런타임을 조립한다. 저장소는 비어 있어야 한다. */
  protected abstract Assembled newRuntime(TiroAdapter adapter, MutableClock clock);

  private final MutableClock clock = new MutableClock(NOW);
  private FakeTiroServer server;
  private Assembled runtime;
  private final TsRuns ts = new TsRuns();

  @BeforeEach
  void setUpContract() {
    server = TiroAssembly.server();
    runtime = newRuntime(TiroAssembly.adapter(server.baseUrl(), server.token(), clock), clock);
    runtime.sources().save(TiroAssembly.instance(server.configJson(), NOW));
  }

  @AfterEach
  void tearDownContract() {
    try {
      server.assertNoUnmatched();
    } finally {
      server.close();
    }
  }

  /** 증분 모드로 수집한다 — Tiro는 모드와 무관하게 complete_snapshot이라 성공하면 정리까지 한다. */
  CollectionRuntime.Report collect() {
    return runtime
        .runtime()
        .collect(TiroAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  List<String> activeIds() {
    List<String> out = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      RawItemStore.Page page = runtime.rawItems().listActive(TiroAssembly.SOURCE, 100, cursor);
      page.items().forEach(i -> out.add(i.externalId()));
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  int tombstones() {
    return runtime.rawItems().tombstoneCount(TiroAssembly.SOURCE);
  }

  StoredCheckpoint storedCheckpoint() {
    return runtime.checkpoints().read(TiroAssembly.SOURCE);
  }

  /** 첫 수집이 남긴 체크포인트 — "그대로다"를 비어 있는 것끼리 비교하지 않게, 없으면 멈춘다. */
  StoredCheckpoint requireStoredCheckpoint() {
    StoredCheckpoint stored = storedCheckpoint();
    if (stored.checkpoint().isEmpty())
      throw new IllegalStateException("given 준비 실패: 첫 수집이 체크포인트를 남기지 않았다");
    return stored;
  }

  String sourceVersion(String externalId) {
    RawRecord record = runtime.rawItems().get(TiroAssembly.SOURCE, externalId).orElseThrow();
    return ((RawRecord.Active) record).item().sourceVersion();
  }

  /**
   * 첫 수집이 succeeded로 끝나 빠질 노트(n-gamma)까지 active로 썼다. 아니면 둘째 수집의 단언이 엉뚱한 이유로 실패하거나 우연히 통과하므로 멈춘다.
   */
  void givenFirstCollectionSucceeded() {
    CollectionRuntime.Report first = collect();
    if (first.status() != RunStatus.SUCCEEDED)
      throw new IllegalStateException("given 준비 실패: 첫 수집이 succeeded로 끝나지 않았다 — " + first);
    if (!activeIds().contains("note:" + REMOVED))
      throw new IllegalStateException("given 준비 실패: 첫 수집이 note:" + REMOVED + "를 쓰지 않았다");
  }

  /**
   * TS가 일반 Error로 던지는 경우의 run errorKind(`error.constructor.name`, CONTRACTS "run errorKind 매핑").
   */
  static final String TS_ERROR = "Error";

  static final String ALL_NOTES = "{\"workspaceGuid\":\"ws-synthetic\",\"allNotes\":true}";

  @Nested
  @DisplayName("처음 수집하면")
  class FirstCollection {
    @Test
    @DisplayName("run 보고가 TS와 같다")
    void firstReportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ts.firstReport());
    }

    @Test
    @DisplayName("active 원본이 TS와 같다")
    void firstActiveItemsMatchTs() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(ts.firstActiveIds());
    }

    @Test
    @DisplayName("상세가 404인 노트는 tombstone으로 남는다")
    void missingNoteIsTombstoned() {
      // when
      collect();

      // then
      assertThat(tombstones()).isEqualTo(ts.firstTombstones());
    }
  }

  @Nested
  @DisplayName("첫 수집 뒤 목록 응답 형식이 틀리면")
  class MalformedListing {
    StoredCheckpoint before;

    @BeforeEach
    void givenMalformedListingAfterFirstCollection() {
      givenFirstCollectionSucceeded();
      before = requireStoredCheckpoint();
      server.answer(LIST, "{}");
    }

    @Test
    @DisplayName("run errorKind는 TS처럼 Error다")
    void storesTsErrorKind() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains(TS_ERROR);
    }

    @Test
    @DisplayName("run 오류 요약은 TS 메시지 그대로다")
    void storesTsMessage() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorSummary()).contains("Invalid Tiro pagination envelope");
    }

    @Test
    @DisplayName("run은 failed다")
    void runFails() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("active 원본을 정리하지 않는다(첫 수집 그대로)")
    void keepsActiveItems() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(ts.firstActiveIds());
    }

    @Test
    @DisplayName("tombstone을 더하지 않는다(첫 수집 그대로)")
    void keepsTombstones() {
      // when
      collect();

      // then
      assertThat(tombstones()).isEqualTo(ts.firstTombstones());
    }

    @Test
    @DisplayName("체크포인트를 바꾸지 않는다")
    void keepsCheckpoint() {
      // when
      collect();

      // then
      assertThat(storedCheckpoint()).isEqualTo(before);
    }
  }

  @Nested
  @DisplayName("수집 범위를 바꾼 뒤 다시 수집하면")
  class ChangedScope {
    StoredCheckpoint before;

    @BeforeEach
    void givenScopeChangedAfterFirstCollection() {
      givenFirstCollectionSucceeded();
      before = requireStoredCheckpoint();
      runtime.sources().save(TiroAssembly.instance(ALL_NOTES, NOW));
    }

    @Test
    @DisplayName("run errorKind는 TS처럼 Error다")
    void storesTsErrorKind() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.errorKind()).contains(TS_ERROR);
    }

    @Test
    @DisplayName("run은 failed다")
    void runFails() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("active 원본을 정리하지 않는다(첫 수집 그대로)")
    void keepsActiveItems() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(ts.firstActiveIds());
    }

    @Test
    @DisplayName("tombstone을 더하지 않는다(첫 수집 그대로)")
    void keepsTombstones() {
      // when
      collect();

      // then
      assertThat(tombstones()).isEqualTo(ts.firstTombstones());
    }

    @Test
    @DisplayName("이전 범위의 체크포인트를 바꾸지 않는다")
    void keepsCheckpoint() {
      // when
      collect();

      // then
      assertThat(storedCheckpoint()).isEqualTo(before);
    }
  }

  @Nested
  @DisplayName("목록에서 노트 하나가 빠진 뒤 다시 수집하면")
  class AfterRemoval {
    @BeforeEach
    void givenRemovedAfterFirstCollection() {
      givenFirstCollectionSucceeded();
      server.removeFromListing(REMOVED);
    }

    @Test
    @DisplayName("run 보고가 TS와 같다")
    void reportMatchesTs() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(ReportView.of(report)).isEqualTo(ts.secondReport());
    }

    @Test
    @DisplayName("빠진 노트는 active 원본에서 사라진다")
    void removedNoteIsSwept() {
      // when
      collect();

      // then
      assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(ts.secondActiveIds());
    }

    @Test
    @DisplayName("빠진 노트는 tombstone이 된다")
    void removedNoteIsTombstoned() {
      // when
      collect();

      // then
      assertThat(tombstones()).isEqualTo(ts.secondTombstones());
    }
  }

  @Nested
  @DisplayName("노트가 빠진 뒤 둘째 목록 페이지가 계속 실패하면")
  class FailedAfterRemoval {
    @BeforeEach
    void givenSecondPageFailsAfterRemoval() {
      givenFirstCollectionSucceeded();
      server.removeFromListing(REMOVED);
      server.failOnCursor(LIST, SECOND_PAGE, ATTEMPTS, FakeTiroServer.Fault.status(503));
    }

    @Test
    @DisplayName("run은 partially_succeeded다")
    void runIsPartial() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.status()).isEqualTo(RunStatus.PARTIALLY_SUCCEEDED);
    }

    @Test
    @DisplayName("보지 못한 노트를 정리하지 않는다")
    void doesNotSweep() {
      // when
      collect();

      // then
      assertThat(activeIds()).contains("note:" + REMOVED);
    }
  }

  @Nested
  @DisplayName("전사 하나가 바뀐 뒤 다시 수집하면")
  class AfterEdit {
    static final String ALPHA = "note:n-alpha";

    String before;

    @BeforeEach
    void givenTranscriptEditedAfterFirstCollection() {
      givenFirstCollectionSucceeded();
      before = sourceVersion(ALPHA);
      server.editFirstTranscript("n-alpha", "수정된 전사");
    }

    @Test
    @DisplayName("그 노트만 새로 upsert한다")
    void upsertsOnlyEditedNote() {
      // when
      CollectionRuntime.Report report = collect();

      // then
      assertThat(report.upserted()).isOne();
    }

    @Test
    @DisplayName("그 노트의 sourceVersion이 바뀐다")
    void changesSourceVersion() {
      // when
      collect();

      // then
      assertThat(sourceVersion(ALPHA)).isNotEqualTo(before);
    }
  }

  /** TS 런타임이 같은 데이터셋에서 낸 두 수집의 결과. */
  static final class TsRuns {
    private final JsonNode root;

    TsRuns() {
      try {
        root =
            TiroAssembly.JSON.readTree(
                Files.readString(TiroAssembly.FIXTURES.resolve("expected-raw-items.json")));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /** 첫 수집(파일 최상위). */
    ReportView firstReport() {
      return ReportView.expected(root.get("report"));
    }

    int firstTombstones() {
      return root.get("tombstoneCount").asInt();
    }

    /** 목록에서 노트가 빠진 뒤의 두 번째 수집(secondRun). */
    ReportView secondReport() {
      return ReportView.expected(root.get("secondRun").get("report"));
    }

    int secondTombstones() {
      return root.get("secondRun").get("tombstoneCount").asInt();
    }

    List<String> firstActiveIds() {
      List<String> out = new ArrayList<>();
      root.get("items").forEach(i -> out.add(i.get("key").get("externalId").asString()));
      return out;
    }

    List<String> secondActiveIds() {
      List<String> out = new ArrayList<>();
      root.get("secondRun").get("activeExternalIds").forEach(i -> out.add(i.asString()));
      return out;
    }
  }
}
