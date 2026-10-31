package io.scos.engine;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.engine.internal.search.SearchFixture;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.Principal;
import io.scos.store.memory.MemoryIdentityStore;
import io.scos.store.memory.MemoryIdentityStore.Directory;
import io.scos.store.memory.MemoryIdentityStore.User;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 엔진 접근 모드 명세의 포트·시계·문서 준비를 소유한다. */
final class ContextEngineAssemblyScenario {
  static final Principal ALICE = new Principal("alice");
  static final Principal UNKNOWN = new Principal("unknown");
  static final String DOCUMENT = "settlement";
  static final SourceInstanceId SOURCE = new SourceInstanceId("drive-a");
  static final Instant CAPTURED = Instant.parse("2026-10-13T00:00:00Z");
  static final Clock CLOCK = Clock.fixed(CAPTURED, ZoneOffset.UTC);
  static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.com"), Map.of(SOURCE, "example.com"));

  private final SearchFixture fixture = new SearchFixture();
  private final MemoryIdentityStore identities = new MemoryIdentityStore();
  private final RecordedSourceFreshness sources = new RecordedSourceFreshness();

  ContextEngineAssemblyScenario() {
    fixture.load(
        SearchFixture.S,
        unit("settlement-1")
            .inDocument(DOCUMENT)
            .titled("정산")
            .content("정산 본문")
            .capturedAt(CAPTURED.toString())
            .embedding(1, 0)
            .linksTo("related")
            .build(),
        unit("related-1")
            .inDocument("related")
            .titled("정산 관련")
            .content("정산 안내")
            .capturedAt(CAPTURED.toString())
            .embedding(1, 0)
            .build());
    sources.recordSuccess(SOURCE, CAPTURED);
    registerAlice();
  }

  void registerAlice() {
    identities.replace(
        new Directory(
            List.of(new User("alice", "alice@example.com", true)),
            List.of(),
            List.of(),
            List.of(),
            Set.of()));
  }

  void deactivateAliceAfterRequest(DefaultContextEngine engine) {
    var initial = engine.searchRecipe(ALICE, SearchQuery.of("정산")).access();
    if (!(initial instanceof AccessScope.Restricted restricted) || restricted.keys().isEmpty())
      throw new IllegalStateException("given 준비 실패: 첫 요청에는 Alice의 키가 있어야 한다");
    identities.replace(
        new Directory(
            List.of(new User("alice", "alice@example.com", false)),
            List.of(),
            List.of(),
            List.of(),
            Set.of()));
  }

  ContextEngineBuilder withoutMode() {
    return withoutVectors().vectors(fixture.vectors).embedder(FixedEmbedder.zero(2));
  }

  ContextEngineBuilder withoutVectors() {
    return ContextEngine.builder()
        .units(fixture.units)
        .keywords(fixture.keywords)
        .clock(CLOCK)
        .lexicalExecutor(TestExecutors.CALLER_THREAD);
  }

  ContextEngineBuilder builder(AccessMode mode) {
    return selectMode(withoutMode(), mode);
  }

  ContextEngineBuilder selectMode(ContextEngineBuilder builder, AccessMode mode) {
    return switch (mode) {
      case UNRESTRICTED -> builder.unrestricted(fixture.maintenance);
      case RESTRICTED -> builder.access(identities, sources, DOMAINS);
    };
  }

  ContextEngineBuilder keywordOnlyWithoutVectors() {
    return withoutVectors().unrestricted(fixture.maintenance).keywordOnly();
  }

  ContextEngine unrestricted() {
    return withoutMode().unrestricted(fixture.maintenance).build();
  }

  ContextEngineBuilder restrictedBuilder() {
    return withoutMode().access(identities, sources, DOMAINS);
  }

  DefaultContextEngine restricted() {
    return (DefaultContextEngine) restrictedBuilder().build();
  }
}
