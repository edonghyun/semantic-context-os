package io.scos.engine.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryEmbeddingCache;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 공간 재임베딩(stage2 §3.3): pending 표시 → 모든 유닛을 목표 임베더로 다시 임베딩(캐시 우선, 100개씩) → 목표 세대 기록. 중간에 죽으면
 * pending이 남고, 다시 실행하면 끝까지 간다. 메모리 어댑터로 검증한다.
 */
@DisplayName("공간 재임베딩은 pending을 남기고 모든 유닛을 목표 세대로 다시 임베딩한다")
class SpaceReembeddingTest {
  static final SpaceId S = new SpaceId("legacy");
  static final String OLD_GENERATION = "gemini:gemini-embedding-001:768:title+aliases+content";
  static final double[] OLD_VECTOR = {0, 0, 0, 1};
  static final double[] CACHED_VECTOR = {9, 8, 7, 6};

  /** 두 배치(100 + 50)에 걸치는 유닛 수. */
  static final int UNITS = 150;

  MemoryUnitStore units = new MemoryUnitStore();
  MemoryVectorIndex vectors = new MemoryVectorIndex();
  public final MemoryUnitMaintenance maintenance = new MemoryUnitMaintenance(units, vectors);
  MemorySpaceMeta meta = new MemorySpaceMeta();
  MemoryEmbeddingCache cache = new MemoryEmbeddingCache();
  RecordingEmbedder embedder = new RecordingEmbedder();
  SpaceReembedding reembedding =
      new SpaceReembedding(
          TransactionOperations.withoutTransaction(), maintenance, vectors, meta, embedder, cache);
  EmbeddingGeneration target = EmbeddingGenerationCheck.generationOf(embedder);

  static ContextUnit unit(int i) {
    return ContextUnits.unit("u" + i)
        .inDocument("doc-" + i)
        .titled("제목 " + i)
        .content("본문 " + i)
        .build();
  }

  /** 옛 세대로 임베딩된 유닛 n개가 있는 공간. */
  List<ContextUnit> givenOldSpace(int n) {
    List<ContextUnit> all = IntStream.range(0, n).mapToObj(SpaceReembeddingTest::unit).toList();
    maintenance.upsert(S, all);
    vectors.upsert(
        S,
        all.stream()
            .map(u -> new IndexedUnit(u.id(), u.documentId(), u.partition(), false, OLD_VECTOR))
            .toList());
    meta.set(S, EmbeddingGenerationCheck.META_KEY, OLD_GENERATION);
    return all;
  }

  Optional<String> storedGeneration() {
    return meta.get(S, EmbeddingGenerationCheck.META_KEY);
  }

  List<double[]> storedVectors(List<ContextUnit> all) {
    Map<String, double[]> byId =
        vectors.vectorsOf(S, AccessScope.singleUser(), all.stream().map(ContextUnit::id).toList());
    List<double[]> out = new ArrayList<>();
    for (ContextUnit u : all) out.add(byId.get(u.id()));
    return out;
  }

  static List<double[]> ownVectors(List<ContextUnit> all) {
    return all.stream()
        .map(u -> RecordingEmbedder.vectorOf(EmbedderUnitEmbedding.inputOf(u)))
        .toList();
  }

  @Test
  @DisplayName("재임베딩 중에는 세대가 pending:<목표>다")
  void pendingWhileReembedding() {
    // given: 첫 임베딩 호출 때 공간 세대를 엿본다
    givenOldSpace(3);
    AtomicReference<Optional<String>> seen = new AtomicReference<>();
    embedder.beforeNextEmbed(() -> seen.set(storedGeneration()));

    // when
    reembedding.run(S);

    // then
    assertThat(seen.get()).contains(EmbeddingGenerationCheck.PENDING_PREFIX + target.value());
  }

  @Test
  @DisplayName("끝나면 목표 세대를 기록한다")
  void recordsTargetGeneration() {
    // given
    givenOldSpace(3);

    // when
    reembedding.run(S);

    // then
    assertThat(storedGeneration()).contains(target.value());
  }

  @Test
  @DisplayName("모든 유닛의 벡터를 목표 임베더 벡터로 바꾼다")
  void replacesEveryVector() {
    // given: 두 배치에 걸치는 공간
    List<ContextUnit> all = givenOldSpace(UNITS);

    // when
    SpaceReembedding.Report report = reembedding.run(S);

    // then
    assertThat(storedVectors(all)).containsExactlyElementsOf(ownVectors(all));
    assertThat(report)
        .isEqualTo(
            new SpaceReembedding.Report(
                S, UNITS, 0, UNITS, target, SpaceReembedding.Outcome.REEMBEDDED));
  }

  @Test
  @DisplayName("캐시에 있는 입력은 임베더에 보내지 않는다")
  void cachedInputsSkipEmbedder() {
    // given: 목표 세대 캐시에 앞 두 유닛의 입력이 있다
    List<ContextUnit> all = givenOldSpace(3);
    cache.putIfAbsent(
        target.value(),
        all.subList(0, 2).stream()
            .map(EmbedderUnitEmbedding::inputOf)
            .collect(Collectors.toMap(CachedEmbeddings::keyOf, i -> CACHED_VECTOR)));

    // when
    SpaceReembedding.Report report = reembedding.run(S);

    // then
    assertThat(embedder.calls).containsExactly(EmbedderUnitEmbedding.inputOf(all.get(2)));
    assertThat(report)
        .isEqualTo(
            new SpaceReembedding.Report(S, 3, 2, 1, target, SpaceReembedding.Outcome.REEMBEDDED));
  }

  @Test
  @DisplayName("임베더가 중간에 실패하면 pending이 남는다")
  void failureLeavesPending() {
    // given: 둘째 배치의 유닛 하나에서 실패한다
    givenOldSpace(UNITS);
    embedder.failsWith429On("본문 120");

    // when
    var unused = catchThrowable(() -> reembedding.run(S));

    // then
    assertThat(storedGeneration())
        .contains(EmbeddingGenerationCheck.PENDING_PREFIX + target.value());
  }

  @Test
  @DisplayName("pending에서 다시 실행하면 끝까지 간다")
  void rerunFromPendingFinishes() {
    // given: 중간에 실패해 pending이 남은 공간
    List<ContextUnit> all = givenOldSpace(UNITS);
    embedder.failsWith429On("본문 120");
    if (catchThrowable(() -> reembedding.run(S)) == null)
      throw new IllegalStateException("given 준비 실패: 첫 재임베딩이 실패하지 않았다");
    embedder.recovers();

    // when
    reembedding.run(S);

    // then
    assertThat(storedVectors(all)).containsExactlyElementsOf(ownVectors(all));
  }

  @Test
  @DisplayName("이미 목표 세대면 아무것도 쓰지 않는다")
  void targetGenerationIsNoOp() {
    // given
    List<ContextUnit> all = givenOldSpace(3);
    meta.set(S, EmbeddingGenerationCheck.META_KEY, target.value());

    // when
    reembedding.run(S);

    // then
    assertThat(storedVectors(all)).containsOnly(OLD_VECTOR);
  }

  @Test
  @DisplayName("계획은 유닛 수·저장 세대·목표 세대만 내고 쓰지 않는다")
  void planWritesNothing() {
    // given
    givenOldSpace(3);

    // when
    SpaceReembedding.Plan plan = reembedding.plan(S);

    // then
    assertThat(List.of(plan, storedGeneration(), embedder.calls))
        .containsExactly(
            new SpaceReembedding.Plan(S, 3, Optional.of(OLD_GENERATION), target),
            Optional.of(OLD_GENERATION),
            List.of());
  }

  @Test
  @DisplayName("다른 재임베딩이 pending을 가져가면 남은 벡터를 쓰지 않고 멈춘다")
  void stopsWhenAnotherReembeddingTakesOver() {
    // given: 첫 임베딩 호출 때 다른 프로세스가 다른 목표로 재임베딩을 시작한다
    List<ContextUnit> all = givenOldSpace(3);
    String other = EmbeddingGenerationCheck.PENDING_PREFIX + "other:model:4:title+aliases+content";
    embedder.beforeNextEmbed(() -> meta.set(S, EmbeddingGenerationCheck.META_KEY, other));

    // when
    var unused = catchThrowable(() -> reembedding.run(S));

    // then
    assertThat(storedVectors(all)).containsOnly(OLD_VECTOR);
  }

  @Test
  @DisplayName("임베딩 도중 다른 실행이 목표 세대를 기록하면 남은 벡터를 쓰지 않는다")
  void stopsWhenAnotherRunCompleted() {
    // given: 첫 임베딩 호출 때 같은 목표의 다른 실행이 끝나 목표 세대를 기록한다
    List<ContextUnit> all = givenOldSpace(UNITS);
    embedder.beforeNextEmbed(() -> meta.set(S, EmbeddingGenerationCheck.META_KEY, target.value()));

    // when
    var unused = catchThrowable(() -> reembedding.run(S));

    // then
    assertThat(storedVectors(all)).containsOnly(OLD_VECTOR);
  }

  @Test
  @DisplayName("임베딩 도중 다른 실행이 목표 세대를 기록하면 실패 없이 다른 실행이 끝냈다고 보고한다")
  void reportsCompletionByAnotherRun() {
    // given: 첫 임베딩 호출 때 같은 목표의 다른 실행이 끝나 목표 세대를 기록한다
    givenOldSpace(UNITS);
    embedder.beforeNextEmbed(() -> meta.set(S, EmbeddingGenerationCheck.META_KEY, target.value()));

    // when
    SpaceReembedding.Report report = reembedding.run(S);

    // then
    assertThat(report.outcome()).isEqualTo(SpaceReembedding.Outcome.COMPLETED_BY_OTHER_RUN);
  }

  @Test
  @DisplayName("유닛도 저장 세대도 없는 공간이면 pending도 세대도 쓰지 않는다")
  void emptySpaceWritesNothing() {
    // when: 아무것도 없는 공간(잘못 적은 공간 id)
    var unused = reembedding.run(new SpaceId("legcy"));

    // then
    assertThat(meta.get(new SpaceId("legcy"), EmbeddingGenerationCheck.META_KEY)).isEmpty();
  }

  @Test
  @DisplayName("유닛도 저장 세대도 없는 공간이면 빈 공간이라고 보고한다")
  void reportsEmptySpace() {
    // when
    SpaceReembedding.Report report = reembedding.run(new SpaceId("legcy"));

    // then
    assertThat(report)
        .isEqualTo(
            new SpaceReembedding.Report(
                new SpaceId("legcy"), 0, 0, 0, target, SpaceReembedding.Outcome.EMPTY_SPACE));
  }
}
