package io.scos.engine;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.access.RecordedSourceFreshness;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.Principal;
import io.scos.spi.store.SpaceId;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** 같은 적재 순서의 전체 코퍼스와 보이는 문서만 담은 무제한 코퍼스를 조립한다. */
final class AccessScenario {
  static final Principal ALICE = new Principal("alice");
  static final Principal BOB = new Principal("bob");
  static final Principal CAROL = new Principal("carol");
  static final Principal DAVE = new Principal("dave");
  static final Principal ERIN = new Principal("erin");
  static final Principal GINA = new Principal("gina");
  static final SpaceId SPACE = SpaceId.LEGACY;
  static final SpaceId OTHER_SPACE = new SpaceId("other");
  static final String PUBLIC = "P";
  static final String PRIVATE = "Q";
  static final String UNKNOWN = "U";
  static final String FAILED = "F";
  static final String NO_ACL = "N";
  static final String DESIGNATED = "S";
  static final String MISSING = "missing";
  static final String QUERY = "정산";
  static final String LINK_QUERY = "링크시작";
  static final String COMPACTED_ID = "C";
  static final String SUMMARY_CONTENT = "정산 숨은 문서를 합친 요약";
  static final int ORIGINALS_BEFORE_SUMMARY = 2;
  static final int ORIGINALS_WITH_DRILL_DOWN = 3;
  static final int SINGLE_UNIT_LIMIT = 1;
  static final int BEYOND_END = 10_000;
  static final int SMALL_BUDGET = AssembleQuery.MIN_TOKEN_BUDGET;
  static final int LARGE_UNIT_TOKENS = 300;
  static final int SMALL_UNIT_TOKENS = 100;
  static final Instant COLLECTED = Instant.parse("2026-10-25T00:00:00Z");
  static final Instant NEWER_CAPTURED = Instant.parse("2026-10-26T00:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);
  private static final SourceInstanceId SLACK = new SourceInstanceId("slack-a");
  private static final SourceInstanceId NOTION = new SourceInstanceId("notion-a");
  private static final AccessKey PUBLIC_KEY = AccessKey.parse("group:slack-a:C1");
  private static final AccessKey PRIVATE_KEY = AccessKey.parse("group:slack-a:C2");
  private static final AccessKey SCOPE_KEY = AccessKey.parse("scope:notion-a:*");
  private static final double[] MATCH_VECTOR = {1, 0};
  private static final DomainPolicy DOMAINS = new DomainPolicy(Set.of(), Map.of());

  private final Supplier<AccessAdapters> newAdapters;
  private final AccessAdapters adapters;
  private final RecordedSourceFreshness freshness = new RecordedSourceFreshness();
  private final List<ContextUnit> corpus = new ArrayList<>();

  AccessScenario() {
    this(AccessAdapters::memory);
  }

  AccessScenario(Supplier<AccessAdapters> newAdapters) {
    this(newAdapters, List.of());
    adapters.seed().accept(directory());
    freshness.recordSuccess(SLACK, COLLECTED);
    freshness.recordSuccess(NOTION, NEWER_CAPTURED);
    load(SPACE, document("p1", PUBLIC).linksTo(PRIVATE, DESIGNATED).build());
    load(SPACE, document("p2", PUBLIC).linksTo(PRIVATE).build());
    load(
        SPACE,
        document("q1", PRIVATE).linksTo(PUBLIC).capturedAt(NEWER_CAPTURED.toString()).build());
    load(SPACE, document("q2", PRIVATE).build(), document("q3", PRIVATE).build());
    load(SPACE, document("u1", UNKNOWN).build(), document("f1", FAILED).build());
    load(SPACE, document("n1", NO_ACL).build());
    load(SPACE, document("s1", DESIGNATED).tokens(SMALL_UNIT_TOKENS).build());
    adapters
        .acls()
        .put(
            SPACE,
            List.of(
                acl(PUBLIC, DocumentAcl.Status.OK, PUBLIC_KEY),
                acl(PRIVATE, DocumentAcl.Status.OK, PRIVATE_KEY),
                acl(UNKNOWN, DocumentAcl.Status.UNKNOWN, PUBLIC_KEY),
                acl(FAILED, DocumentAcl.Status.FAILED, PUBLIC_KEY),
                acl(DESIGNATED, DocumentAcl.Status.UNSUPPORTED, SCOPE_KEY)));
  }

  private AccessScenario(Supplier<AccessAdapters> newAdapters, List<ContextUnit> visible) {
    this.newAdapters = newAdapters;
    adapters = newAdapters.get();
    load(SPACE, visible.toArray(ContextUnit[]::new));
  }

  private ContextUnits.Builder document(String id, String document) {
    return unit(id)
        .inDocument(document)
        .source(document.equals(DESIGNATED) ? NOTION.value() : SLACK.value())
        .titled(QUERY + " " + document)
        .content(QUERY)
        .tokens(LARGE_UNIT_TOKENS)
        .capturedAt(COLLECTED.toString())
        .embedding(MATCH_VECTOR);
  }

  private DocumentAcl acl(String document, DocumentAcl.Status status, AccessKey key) {
    return new DocumentAcl(document, status, Set.of(key), COLLECTED);
  }

  private AccessDirectory directory() {
    return AccessDirectory.empty()
        .withUser(new AccessDirectory.User(ALICE.userId(), "alice@example.com", true))
        .withUser(new AccessDirectory.User(BOB.userId(), "bob@example.com", true))
        .withUser(new AccessDirectory.User(CAROL.userId(), "carol@example.com", false))
        .withUser(new AccessDirectory.User(ERIN.userId(), "erin@example.com", true))
        .withUser(new AccessDirectory.User(GINA.userId(), "gina@example.com", true))
        .withIdentity(new AccessDirectory.Identity(SLACK, "U1", false, false))
        .withIdentity(new AccessDirectory.Identity(SLACK, "U2", false, false))
        .withIdentity(new AccessDirectory.Identity(SLACK, "UG", true, false))
        .withIdentity(new AccessDirectory.Identity(SLACK, "UC", false, false))
        .withLink(new AccessDirectory.Link(SLACK, "U1", ALICE.userId(), "admin"))
        .withLink(new AccessDirectory.Link(SLACK, "U2", BOB.userId(), "admin"))
        .withLink(new AccessDirectory.Link(SLACK, "UC", CAROL.userId(), "admin"))
        .withLink(new AccessDirectory.Link(SLACK, "UG", GINA.userId(), "admin"))
        .withGroup(new AccessDirectory.Group(SLACK, "~members", Set.of("U1", "UC")))
        .withGroup(new AccessDirectory.Group(SLACK, "C1", Set.of("~members", "UG")))
        .withGroup(new AccessDirectory.Group(SLACK, "C2", Set.of("U2")))
        .withScope(NOTION);
  }

  private void load(SpaceId space, ContextUnit... batch) {
    if (space.equals(SPACE)) corpus.addAll(List.of(batch));
    adapters.load(space, List.of(batch));
  }

  private ContextEngineBuilder builder() {
    return ContextEngine.builder()
        .units(adapters.units())
        .keywords(adapters.keywords())
        .vectors(adapters.vectors())
        .embedder(FixedEmbedder.byKeyword(QUERY, MATCH_VECTOR, new double[MATCH_VECTOR.length]))
        .clock(CLOCK)
        .lexicalExecutor(TestExecutors.CALLER_THREAD);
  }

  ContextEngine restricted() {
    return builder().access(adapters.identities(), freshness, DOMAINS).build();
  }

  ContextEngine unrestricted() {
    return builder().unrestricted(adapters.maintenance()).build();
  }

  ContextEngine visibleOnly(Principal principal) {
    Set<String> visible =
        principal.equals(ALICE)
            ? Set.of(PUBLIC, DESIGNATED)
            : principal.equals(BOB) ? Set.of(PRIVATE, DESIGNATED) : Set.of(DESIGNATED);
    AccessScenario only =
        new AccessScenario(
            newAdapters, corpus.stream().filter(u -> visible.contains(u.documentId())).toList());
    return only.unrestricted();
  }

  RelatedResult givenVisibleRelated() {
    var expected = visibleOnly(ALICE).related(ALICE, RelatedQuery.of(PUBLIC));
    if (!expected.documents().stream()
        .map(RelatedDocument::documentId)
        .toList()
        .equals(List.of(DESIGNATED)))
      throw new IllegalStateException("given 준비 실패: 관련 문서 오라클은 지정 문서 하나여야 한다");
    return expected;
  }

  ContextBundle givenTruncatedVisibleBundle(AssembleQuery query) {
    var expected = visibleOnly(ALICE).assemble(ALICE, query);
    if (!expected.truncated())
      throw new IllegalStateException("given 준비 실패: 작은 예산 오라클은 후보를 잘라야 한다");
    return expected;
  }

  ReadRequest readAt(String document, int offset) {
    return new ReadRequest(Optional.empty(), document, offset, ReadRequest.DEFAULT_LIMIT);
  }

  DocumentNotFoundException absentRead(String document, int offset) {
    try {
      visibleOnly(ALICE).read(ALICE, readAt(document, offset));
      throw new IllegalStateException("given 준비 실패: 오라클에 차단 문서가 있다");
    } catch (DocumentNotFoundException missing) {
      return missing;
    }
  }

  DocumentNotFoundException absentRelated(String document) {
    try {
      visibleOnly(ALICE).related(ALICE, RelatedQuery.of(document));
      throw new IllegalStateException("given 준비 실패: 오라클에 차단 시드가 있다");
    } catch (DocumentNotFoundException missing) {
      return missing;
    }
  }

  AccessScenario withOtherSpace() {
    // 문서 소유는 전역 유일하다. 같은 문서 id의 ACL만 공간별로 달리 두고 유닛은 B에 둔다.
    AccessScenario other =
        new AccessScenario(
            newAdapters, corpus.stream().filter(u -> !u.documentId().equals(PUBLIC)).toList());
    other.adapters.seed().accept(directory());
    other
        .adapters
        .acls()
        .put(
            SPACE,
            new ArrayList<>(
                adapters
                    .acls()
                    .get(SPACE, List.of(PUBLIC, PRIVATE, UNKNOWN, FAILED, DESIGNATED))
                    .values()));
    other.load(OTHER_SPACE, document("p1", PUBLIC).build());
    other
        .adapters
        .acls()
        .put(OTHER_SPACE, List.of(acl(PUBLIC, DocumentAcl.Status.OK, PRIVATE_KEY)));
    return other;
  }

  SearchQuery otherSpaceQuery() {
    return new SearchQuery(Optional.of(OTHER_SPACE), QUERY, SearchQuery.MAX_LIMIT);
  }

  void givenCompactedSummary() {
    load(
        SPACE,
        document(COMPACTED_ID, PUBLIC)
            .content(SUMMARY_CONTENT)
            .tokens(SMALL_UNIT_TOKENS)
            .compacted("p1", "q1", "drill-only")
            .build());
    load(
        SPACE,
        document("drill-only", PUBLIC).titled("원본전용").content("원본전용").withoutEmbedding().build());
  }

  void givenCompactedSeedPrefix() {
    for (String id : List.of("p1", "p2", COMPACTED_ID, "C2", "C3"))
      load(SPACE, document(id, PUBLIC).content(SUMMARY_CONTENT).compacted("q1").build());
    for (String id : List.of("r1", "r2", "r3", "r4"))
      load(SPACE, document(id, PUBLIC).withoutEmbedding().build());
    load(SPACE, document("r5", PUBLIC).withoutEmbedding().linksTo(DESIGNATED).build());
    // 여섯 번째 원본의 벡터는 앞 다섯 시드에 끼면 안 된다.
    load(SPACE, document("r6", PUBLIC).build());
  }

  SearchQuery givenSummaryLinkStart() {
    load(
        SPACE,
        document("s1", DESIGNATED)
            .titled(LINK_QUERY)
            .content(LINK_QUERY)
            .linksTo(PUBLIC)
            .withoutEmbedding()
            .build());
    adapters.vectors().delete(SPACE, List.of("s1"));
    return new SearchQuery(Optional.empty(), LINK_QUERY, SearchQuery.MAX_LIMIT);
  }

  void givenOnlyCompactedSeeds() {
    for (String id : List.of("p1", "p2"))
      load(SPACE, document(id, PUBLIC).content(SUMMARY_CONTENT).compacted("q1").build());
  }

  SearchQuery givenLinkStart() {
    load(
        SPACE,
        document("p1", PUBLIC)
            .titled(LINK_QUERY)
            .content(LINK_QUERY)
            .linksTo(PRIVATE, DESIGNATED)
            .withoutEmbedding()
            .build());
    adapters.vectors().delete(SPACE, List.of("p1"));
    return new SearchQuery(Optional.empty(), LINK_QUERY, SearchQuery.MAX_LIMIT);
  }

  SearchQuery searchQuery() {
    return new SearchQuery(Optional.empty(), QUERY, SearchQuery.MAX_LIMIT);
  }
}
