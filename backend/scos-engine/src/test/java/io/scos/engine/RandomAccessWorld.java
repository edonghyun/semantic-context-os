package io.scos.engine;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.spi.access.AccessDirectory;
import io.scos.spi.access.AccessKey;
import io.scos.spi.access.DocumentAcl;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.ContextUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** 고정 시드로 설계 §9의 작은 접근 세계를 만든다. 생성 결과는 읽기만 한다. */
record RandomAccessWorld(
    long seed,
    AccessDirectory directory,
    DomainPolicy domains,
    List<ContextUnit> corpus,
    Map<String, DocumentAcl> acls) {
  static final List<String> QUERIES = List.of("정산", "설계", "운영");
  static final Instant OBSERVED = Instant.parse("2026-10-25T00:00:00Z");
  private static final List<SourceInstanceId> INSTANCES =
      List.of(new SourceInstanceId("slack-a"), new SourceInstanceId("notion-a"));
  // a9가 정한 생성 크기 상한. 이 범위 안에서 모든 사용자·문서를 순회한다.
  private static final int MIN_USERS = 3;
  private static final int MAX_USERS = 8;
  private static final int MAX_GROUPS = 6;
  private static final int MAX_SYNTHETIC = 2;
  private static final int MIN_DOCUMENTS = 10;
  private static final int MAX_DOCUMENTS = 40;
  private static final int UNIT_TOKENS = 100;
  private static final int MAX_ACL_KEYS = 3;
  private static final int IDENTITY_STATES = 4;
  private static final List<DocumentAcl.Status> ACL_STATES =
      List.of(
          DocumentAcl.Status.OK,
          DocumentAcl.Status.UNKNOWN,
          DocumentAcl.Status.FAILED,
          DocumentAcl.Status.UNSUPPORTED);
  private static final int MISSING_ACL_STATE = ACL_STATES.size();

  RandomAccessWorld {
    corpus = List.copyOf(corpus);
    acls = Map.copyOf(acls);
  }

  static RandomAccessWorld generate(long seed) {
    var random = new Random(seed);
    var directory = directory(random);
    var domains =
        new DomainPolicy(
            Set.of("example.com"),
            Map.of(INSTANCES.getFirst(), "example.com", INSTANCES.getLast(), "outside.example"));
    var corpus = new ArrayList<ContextUnit>();
    var acls = new LinkedHashMap<String, DocumentAcl>();
    int count = random.nextInt(MIN_DOCUMENTS, MAX_DOCUMENTS + 1);
    var pool = keyPool(directory);
    for (int i = 0; i < count; i++) {
      String id = "D" + i;
      corpus.add(document(random, id, count));
      int status = random.nextInt(MISSING_ACL_STATE + 1);
      if (status == MISSING_ACL_STATE) continue;
      var state = ACL_STATES.get(status);
      var keys = new HashSet<AccessKey>();
      int size = random.nextInt(MAX_ACL_KEYS + 1);
      for (int k = 0; k < size; k++)
        keys.add(
            state == DocumentAcl.Status.UNSUPPORTED
                ? new AccessKey(AccessKey.Kind.SCOPE, choose(random, INSTANCES), "*")
                : choose(random, pool));
      acls.put(id, new DocumentAcl(id, state, keys, OBSERVED));
    }
    return new RandomAccessWorld(seed, directory, domains, corpus, acls);
  }

  private static AccessDirectory directory(Random random) {
    var directory = AccessDirectory.empty();
    int users = random.nextInt(MIN_USERS, MAX_USERS + 1);
    for (int i = 0; i < users; i++) {
      String user = "user-" + i;
      String domain = random.nextBoolean() ? "example.com" : "outside.example";
      directory =
          directory.withUser(
              new AccessDirectory.User(user, user + "@" + domain, random.nextBoolean()));
      for (var instance : INSTANCES) {
        if (random.nextInt(IDENTITY_STATES) != 0)
          directory =
              directory.withIdentity(
                  new AccessDirectory.Identity(
                      instance,
                      "U" + i,
                      random.nextInt(IDENTITY_STATES) == 0,
                      random.nextInt(IDENTITY_STATES) == 0));
        if (random.nextBoolean())
          directory =
              directory.withLink(new AccessDirectory.Link(instance, "U" + i, user, "admin"));
      }
    }
    int totalGroups = random.nextInt(MAX_GROUPS + 1);
    int firstGroups = random.nextInt(totalGroups + 1);
    directory = groups(random, directory, INSTANCES.getFirst(), users, firstGroups);
    directory = groups(random, directory, INSTANCES.getLast(), users, totalGroups - firstGroups);
    if (random.nextBoolean()) directory = directory.withScope(INSTANCES.getLast());
    return directory;
  }

  private static AccessDirectory groups(
      Random random, AccessDirectory directory, SourceInstanceId instance, int users, int count) {
    int synthetic = random.nextInt(Math.min(count, MAX_SYNTHETIC) + 1);
    for (int i = 0; i < count; i++) {
      var members = new HashSet<String>();
      for (int u = 0; u < users; u++) if (random.nextBoolean()) members.add("U" + u);
      // 합성→합성 참조도 섞어 두 단계 참조가 확장되지 않는지 확인한다.
      for (int nested = 0; nested < synthetic; nested++)
        if (random.nextBoolean()) members.add("~G" + nested);
      String id = i < synthetic ? "~G" + i : "G" + i;
      directory = directory.withGroup(new AccessDirectory.Group(instance, id, members));
    }
    return directory;
  }

  private static List<AccessKey> keyPool(AccessDirectory directory) {
    var pool = new ArrayList<AccessKey>();
    for (var instance : INSTANCES) {
      pool.add(new AccessKey(AccessKey.Kind.SCOPE, instance, "*"));
      pool.add(new AccessKey(AccessKey.Kind.DOMAIN, instance, "example.com"));
      for (int i = 0; i < directory.users().size(); i++)
        pool.add(new AccessKey(AccessKey.Kind.USER, instance, "U" + i));
    }
    for (var group : directory.groups())
      pool.add(new AccessKey(AccessKey.Kind.GROUP, group.instance(), group.id()));
    return pool;
  }

  private static ContextUnit document(Random random, String id, int count) {
    var words = new ArrayList<String>();
    for (String query : QUERIES) if (random.nextBoolean()) words.add(query);
    var links = new ArrayList<String>();
    for (int i = 0; i < count; i++) if (random.nextInt(count) == 0) links.add("D" + i);
    String text = String.join(" ", words);
    double[] vector = {random.nextDouble(), random.nextDouble()};
    return unit("unit-" + id)
        .inDocument(id)
        .source(choose(random, INSTANCES).value())
        .titled(text + " " + id)
        .content(text)
        .linksTo(links.toArray(String[]::new))
        .tokens(UNIT_TOKENS)
        .capturedAt(OBSERVED.toString())
        .embedding(vector)
        .build();
  }

  private static <T> T choose(Random random, List<T> values) {
    return values.get(random.nextInt(values.size()));
  }
}
