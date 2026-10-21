package io.scos.collection.internal.postgres;

import static io.scos.collection.internal.RawItems.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.BlobGarbageCollector;
import io.scos.collection.internal.GracePeriod;
import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SweepMode;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import io.scos.spi.connector.SourceInstanceId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** F9 SQL 의미: 참조 중(FK 반대편) blob은 남고, 참조 밖이라도 유예 안이면 남고, 참조 밖·유예 밖만 지운다. DRY_RUN은 지우지 않는다. */
@DisplayName("blob 정리는 참조 밖·유예 밖 blob만 지운다(F9)")
@Tag("db")
class PostgresBlobMaintenanceTest {
  static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  static final SourceInstanceId SOURCE = new SourceInstanceId("src-a");
  static final GracePeriod GRACE = GracePeriod.ofDays(30);
  JdbcClient jdbc;
  PostgresCollectionConfiguration.Adapters adapters;
  BlobGarbageCollector gc;

  @BeforeEach
  void setUp() {
    PostgresSupport.truncateAll();
    jdbc = JdbcClient.create(PostgresSupport.dataSource());
    adapters = PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
    gc = new BlobGarbageCollector(adapters.blobs(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  void orphan(String sha, Instant storedAt) {
    jdbc.sql(
            "INSERT INTO raw_blobs (sha256, media_type, size, data, stored_at) VALUES (:sha, 'application/json', 10, :data, :at)")
        .param("sha", sha)
        .param("data", new byte[10])
        .param("at", Timestamp.from(storedAt))
        .update();
  }

  List<String> shas() {
    return jdbc.sql("SELECT sha256 FROM raw_blobs ORDER BY sha256").query(String.class).list();
  }

  static final Duration OLDER_THAN_GRACE = Duration.ofDays(400);

  /** 격리 콘텐츠(64KiB 초과 → blob)를 가진 rejection 하나 — 그 blob은 유예보다 오래됐다. sha를 돌려준다. */
  String givenOldQuarantinedBlob() {
    RunId run = new RunId("run-with-rejection");
    adapters.runs().start(run, SOURCE, NOW);
    RawContent quarantined =
        RawContentCapture.defaults()
            .json("application/json", "{\"text\":\"" + "q".repeat(70_000) + "\"}");
    adapters
        .runs()
        .addRejections(
            run,
            SOURCE,
            NOW,
            List.of(
                new RejectedItem(
                    RejectedItem.FailureKind.INVALID_ITEM,
                    "no ts",
                    Optional.empty(),
                    NOW,
                    Optional.of(quarantined))));
    String sha = ((RawContent.Blob) quarantined).sha256();
    jdbc.sql("UPDATE raw_blobs SET stored_at = :at WHERE sha256 = :sha")
        .param("at", Timestamp.from(NOW.minus(OLDER_THAN_GRACE)))
        .param("sha", sha)
        .update();
    return sha;
  }

  static final String COLLECTED_SHA = "0".repeat(64);

  /** 다른 트랜잭션의 GC가 방금 지운 blob을 가리키는 원본(blob 데이터 없이 참조만). */
  static RawMutation upsertReferencingCollectedBlob() {
    return new RawMutation.Upsert(
        item("refers-collected")
            .version("v1")
            .content(new RawContent.Blob("application/json", COLLECTED_SHA, 10, Optional.empty()))
            .build());
  }

  String blobShaOf(String externalId) {
    return jdbc.sql(
            "SELECT content_sha256 FROM raw_items WHERE source_instance_id = :s AND external_id = :e")
        .param("s", SOURCE.value())
        .param("e", externalId)
        .query(String.class)
        .single();
  }

  @Nested
  @DisplayName("참조 중인 400일 blob과 31일·29일 된 고아가 있으면")
  class ReferencedAndOrphans {
    @BeforeEach
    void givenBlobs() {
      // 참조 중인 blob: 64KiB를 넘는 콘텐츠의 원본(오래됐어도 지우지 않는다)
      RawContent big =
          RawContentCapture.defaults()
              .json("application/json", "{\"text\":\"" + "x".repeat(70_000) + "\"}");
      adapters
          .rawItems()
          .apply(SOURCE, new RawMutation.Upsert(item("big").version("v1").content(big).build()));
      jdbc.sql("UPDATE raw_blobs SET stored_at = :at")
          .param("at", Timestamp.from(NOW.minus(Duration.ofDays(400))))
          .update();
      orphan("old-orphan", NOW.minus(Duration.ofDays(31)));
      orphan("young-orphan", NOW.minus(Duration.ofDays(29)));
    }

    @Test
    @DisplayName("DRY_RUN은 참조 보존·유예 보존·지울 것을 집계한다")
    void dryRunReports() {
      // when
      BlobGarbageCollector.Report report = gc.sweep(GRACE, SweepMode.DRY_RUN);

      // then
      assertThat(report)
          .extracting(
              BlobGarbageCollector.Report::scanned,
              BlobGarbageCollector.Report::deleted,
              BlobGarbageCollector.Report::retainedByReference,
              BlobGarbageCollector.Report::retainedByGrace,
              BlobGarbageCollector.Report::bytesFreed,
              BlobGarbageCollector.Report::mode)
          .containsExactly(3L, 1L, 1L, 1L, 10L, SweepMode.DRY_RUN);
    }

    @Test
    @DisplayName("DRY_RUN은 아무것도 지우지 않는다")
    void dryRunDeletesNothing() {
      // when
      gc.sweep(GRACE, SweepMode.DRY_RUN);

      // then
      assertThat(shas()).hasSize(3);
    }

    @Test
    @DisplayName("sweep 보고는 지운 유예 밖 고아 하나와 그 크기다")
    void sweepReportsDeletedOrphan() {
      // when
      BlobGarbageCollector.Report report = gc.sweep(GRACE, SweepMode.DELETE);

      // then
      assertThat(report)
          .extracting(BlobGarbageCollector.Report::deleted, BlobGarbageCollector.Report::bytesFreed)
          .containsExactly(1L, 10L);
    }

    @Test
    @DisplayName("sweep은 유예 안의 고아를 남긴다")
    void sweepKeepsYoungOrphan() {
      // when
      gc.sweep(GRACE, SweepMode.DELETE);

      // then
      assertThat(shas()).doesNotContain("old-orphan").contains("young-orphan").hasSize(2);
    }

    @Test
    @DisplayName("sweep은 참조 중인 오래된 blob의 데이터를 남긴다")
    void sweepKeepsReferencedBlob() {
      // when
      gc.sweep(GRACE, SweepMode.DELETE);

      // then
      assertThat(adapters.rawItems().blob(blobShaOf("big"))).isPresent();
    }

    @Test
    @DisplayName("유예 0일이면 참조 중인 blob만 남는다")
    void zeroGraceKeepsOnlyReferenced() {
      // when
      gc.sweep(GracePeriod.ofDays(0), SweepMode.DELETE);

      // then
      assertThat(shas()).containsExactly(blobShaOf("big"));
    }

    @Test
    @DisplayName("유예 0일 sweep 보고는 참조 밖 blob 전부를 지운 수로 센다")
    void zeroGraceReportsAllUnreferenced() {
      // when
      BlobGarbageCollector.Report r = gc.sweep(GracePeriod.ofDays(0), SweepMode.DELETE);

      // then
      assertThat(r.deleted()).isEqualTo(2);
    }

    @Test
    @DisplayName("rejection의 격리 콘텐츠가 참조하는 오래된 blob은 남는다")
    void quarantinedBlobIsReferenced() {
      // given
      String quarantined = givenOldQuarantinedBlob();

      // when
      gc.sweep(GRACE, SweepMode.DELETE);

      // then
      assertThat(shas()).contains(quarantined).doesNotContain("old-orphan");
    }

    @Test
    @DisplayName("GC가 지운 blob을 참조하는 원본 쓰기는 저장소 일시 장애다")
    void referenceToCollectedBlobIsTransient() {
      // when: 다음 시도가 blob을 다시 쓴다
      Throwable thrown =
          catchThrowable(() -> adapters.rawItems().apply(SOURCE, upsertReferencingCollectedBlob()));

      // then: 기대 종류는 손으로 쓴다 — 분류기(CollectionRuntime.isStorageFailure)를 기대값으로 쓰지 않는다
      assertThat(thrown)
          .isInstanceOf(TransientDataAccessResourceException.class)
          .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }
  }
}
