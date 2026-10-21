package io.scos.connector.jira.internal;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.postgres.PostgresCollectionObservations;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.jira.JiraAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira 수집의 DB 종단 연결(TESTING §3.6 6층): 가짜 Jira(TS 데이터셋)·PostgreSQL 어댑터·실제 런타임. 종류와 무관한 관찰은 공용 {@link
 * PostgresCollectionObservations}다. 본문은 "무엇을 바꾸고 수집하면 무엇이 남는가"만 쓴다. 만들 때 DB를 비운다.
 */
@Tag("db")
final class JiraCollectionScenario implements AutoCloseable {
  static final String ISSUE = "/rest/api/3/issue/";
  static final String ISSUE_40001 = ISSUE + "40001";
  static final String OPS_COMPONENTS = "/rest/api/3/project/10001/components";

  /** 이슈 40004의 60 MiB 첨부(크기 상한 거부). */
  static final String OVERSIZED_ATTACHMENT = "50003";

  private final MutableClock clock = new MutableClock(JiraScenario.NOW);
  private final AtomicInteger runs = new AtomicInteger();
  private final DataSource ds;
  private final PostgresCollectionObservations observe;
  private final FakeJiraServer jira;

  JiraCollectionScenario() {
    PostgresSupport.truncateAll();
    ds = PostgresSupport.dataSource();
    observe = new PostgresCollectionObservations(ds, JiraAssembly.SOURCE, clock);
    try {
      jira = new FakeJiraServer(JiraFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    PostgresCollectionConfiguration.adapters(ds)
        .sources()
        .save(JiraAssembly.instance(JiraAssembly.SYNTHETIC_SITE, JiraScenario.NOW));
  }

  /** 이슈 40001의 요약을 바꾼다(다음 이슈 요청부터). */
  void issue40001SummaryEdited(String summary) {
    ObjectNode issue = (ObjectNode) datasetIssue40001().deepCopy();
    ((ObjectNode) issue.get("fields")).put("summary", summary);
    jira.respond(
        ISSUE_40001,
        new Response(200, issue.toString(), Map.of("Content-Type", "application/json")));
  }

  /** TS 장애 사례(expected-failures.json의 failure): 그 경로 접두의 요청은 늘 그 상태로 답한다. */
  void givenFailure(JsonNode failure) {
    jira.failAlways(failure);
  }

  /** 저장된 run의 "상태/오류 종류"(가장 최근 run). */
  String latestRunStatus() {
    return observe.runStatus(observe.latestRunId().orElseThrow());
  }

  /** 저장된 체크포인트(없으면 JSON null). */
  JsonNode storedCheckpointOrNull() {
    return observe
        .checkpoint()
        .map(JiraTsRuns::checkpointJson)
        .orElse(JiraFixtures.JSON.nullNode());
  }

  /**
   * 다음 수집부터 데이터셋이 거부를 낳던 자원을 모두 읽을 수 있게 한다(OPS 컴포넌트 403, 40003 댓글 403, 40005 404, 40004의 60 MiB
   * 첨부). 거부가 없어야 run이 succeeded로 끝나고, 그때만 런타임이 complete_snapshot 정리를 한다 — "사라져도 지우지 않는다"는 이 조건에서만
   * 의미가 있다.
   */
  void everyResourceReadable() {
    json(OPS_COMPONENTS, JiraFixtures.JSON.createArrayNode());
    json(
        ISSUE + "40003/comment",
        JiraFixtures.JSON.readTree(
            "{\"startAt\":0,\"maxResults\":100,\"total\":0,\"comments\":[]}"));
    ObjectNode restored = (ObjectNode) datasetIssue("40006").deepCopy();
    restored.put("id", "40005").put("key", "PAY-5");
    json(ISSUE + "40005", restored);
    json(ISSUE + "40005/remotelink", JiraFixtures.JSON.createArrayNode());
    ObjectNode receipts = (ObjectNode) datasetIssue("40004").deepCopy();
    ArrayNode small = JiraFixtures.JSON.createArrayNode();
    receipts
        .get("fields")
        .get("attachment")
        .forEach(
            a -> {
              if (!a.get("id").asString().equals(OVERSIZED_ATTACHMENT)) small.add(a);
            });
    ((ObjectNode) receipts.get("fields")).set("attachment", small);
    json(ISSUE + "40004", receipts);
  }

  private void json(String path, JsonNode body) {
    jira.respond(
        path, new Response(200, body.toString(), Map.of("Content-Type", "application/json")));
  }

  /** 다음 요청부터 이슈 검색 응답에서 이 이슈를 뺀다(공급자 목록에서 사라짐 — 권한 회수·삭제와 구별하지 않는다). */
  void issueSearchOmits(String issueId) {
    jira.omitFromIssueSearch(issueId);
  }

  CollectionRuntime.Report collect() {
    return PostgresCollectionRuntime.over(ds, clock)
        .adapter(JiraAssembly.adapter(jira.baseUrl(), jira.token(), clock))
        .runCounter(runs)
        .build()
        .collect(JiraAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  // ---- 관찰 ----

  /** active 원본의 외부 id → sourceVersion. */
  Map<String, String> activeVersions() {
    return observe.activeVersions();
  }

  /** 이 sha256 blob의 저장된 바이트 수(없으면 empty). */
  Optional<Long> storedBlobSize(String sha256) {
    return observe.blobSize(sha256);
  }

  long tombstones() {
    return observe.tombstones();
  }

  long checkpointRevision() {
    return observe.checkpointRevision();
  }

  /** 가짜 Jira가 받은 첨부 원본 요청 수. */
  long attachmentRequests() {
    return jira.requests().stream()
        .filter(r -> r.path().startsWith("/rest/api/3/attachment/"))
        .count();
  }

  /** 가짜 Jira가 받은 이 이슈 경로(`/rest/api/3/issue/{id}`와 그 아래) 요청 수. */
  long issueRequests(String issueId) {
    String path = "/rest/api/3/issue/" + issueId;
    return jira.requests().stream()
        .filter(r -> r.path().equals(path) || r.path().startsWith(path + "/"))
        .count();
  }

  private static JsonNode datasetIssue40001() {
    return datasetIssue("40001");
  }

  private static JsonNode datasetIssue(String id) {
    for (JsonNode r : JiraFixtures.dataset().get("routes"))
      if (r.get("path").asString().equals(ISSUE + id)) return r.get("responses").get(0).get("json");
    throw new IllegalStateException("데이터셋에 이슈 " + id + "가 없다");
  }

  @Override
  public void close() {
    jira.close();
  }
}
