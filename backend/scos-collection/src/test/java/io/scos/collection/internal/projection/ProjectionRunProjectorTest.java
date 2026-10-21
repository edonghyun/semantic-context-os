package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.collection.internal.RawItems;
import io.scos.collection.internal.SourceInstances;
import io.scos.engine.internal.projection.NotesProjector;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.ContextUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 투영 실행은 소스 kind에 맞는 투영기를 고른다 — Slack 고정이 아니다(G2, ADR-0022). */
@DisplayName("투영 실행은 소스 kind에 맞는 투영기를 고른다(G2, ADR-0022)")
class ProjectionRunProjectorTest {
  static final SourceInstanceId NOTES_SOURCE = new SourceInstanceId("notes-1");
  static final int EMBEDDING_DIM = 2;

  final MemoryProjection fixture = new MemoryProjection().alsoProjecting(new NotesProjector());

  /** notes 투영기를 등록하지 않은 투영(Slack만). */
  final MemoryProjection slackOnly = new MemoryProjection();

  /** 유닛 최소 토큰(10)을 넘는 노트 본문. */
  static final String NOTE =
      "{\"text\":\"배포 체크리스트: 마이그레이션 확인, 지표 대시보드 점검, 롤백 절차 공유, 담당자 호출 순서 정리\"}";

  /** 그 투영 조립에 notes 종류 소스 + 원본 한 건 + 투영 요청을 둔다(같은 준비가 둘이던 것을 하나로, 리뷰 Medium 7). */
  static void notesSourceAwaitingProjection(MemoryProjection projection) {
    projection.sources.save(
        SourceInstances.of(
            NOTES_SOURCE,
            NotesProjector.KIND,
            "{}",
            MemoryProjection.CREDENTIAL_REF,
            MemoryProjection.T0));
    projection.rawItems.apply(NOTES_SOURCE, RawItems.item("n-1").inlineJson(NOTE).upsert());
    projection.outbox.requestProjection(NOTES_SOURCE, MemoryProjection.T0);
  }

  static List<ProjectionRun.Report> project(MemoryProjection projection) {
    return projection
        .projection(FixedEmbedder.zero(EMBEDDING_DIM), new SimpleMeterRegistry())
        .runDue(CancellationToken.none());
  }

  @Nested
  @DisplayName("notes 투영기를 등록한 투영에서 notes 소스를 투영하면")
  class RegisteredKind {
    @BeforeEach
    void givenNotesSourceAwaitingProjection() {
      notesSourceAwaitingProjection(fixture);
    }

    @Test
    @DisplayName("Slack 밖 kind의 소스도 등록된 투영기로 성공한다")
    void projectsRegisteredNonSlackKind() {
      // when
      List<ProjectionRun.Report> reports = project(fixture);

      // then
      assertThat(reports)
          .extracting(ProjectionRun.Report::outcome)
          .containsExactly(ProjectionRun.Outcome.SUCCEEDED);
    }

    @Test
    @DisplayName("그 투영기가 만든 문서가 유닛이 된다")
    void writesUnitsOfThatProjector() {
      // when
      project(fixture);

      // then
      assertThat(fixture.storedUnits())
          .extracting(ContextUnit::documentId)
          .containsOnly(NotesProjector.DOCUMENT);
    }
  }

  @Nested
  @DisplayName("투영기가 등록되지 않은 kind면(ADR-0022 §1)")
  class UnregisteredKind {
    @BeforeEach
    void givenUnregisteredKindAwaitingProjection() {
      notesSourceAwaitingProjection(slackOnly);
    }

    @Test
    @DisplayName("투영을 건너뛰었다고 보고한다")
    void reportsSkipped() {
      // when
      List<ProjectionRun.Report> reports = project(slackOnly);

      // then
      assertThat(reports)
          .extracting(ProjectionRun.Report::outcome)
          .containsExactly(ProjectionRun.Outcome.SKIPPED_UNKNOWN_KIND);
    }

    @Test
    @DisplayName("투영 요청을 끝내 대기 목록에서 뺀다")
    void completesRequest() {
      // when
      project(slackOnly);

      // then
      assertThat(slackOnly.pendingRequests()).doesNotContain(NOTES_SOURCE);
    }
  }
}
