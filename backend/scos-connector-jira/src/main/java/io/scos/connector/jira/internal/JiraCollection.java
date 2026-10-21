package io.scos.connector.jira.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira run 하나가 소유하는 걷기 상태(TS JiraCollection). 작업 큐(`pending`)의 앞에서 배치를 꺼내 처리하고, 배치마다 페이지 하나를 낸다 —
 * 런타임이 페이지를 커밋하므로 체크포인트는 그 페이지까지 확정된 큐다(v1: `{siteUrl, pending, downloadedAttachments}`).
 *
 * <ul>
 *   <li>배치: 이슈·첨부는 같은 종류가 이어지는 동안 동시 {@value #MAX_CONCURRENT_READS}개까지 함께 읽고, 그 밖의 작업은 하나씩. 배치 안 한
 *       작업이 일시 장애로 끝나면 성공한 형제도 확정하지 않는다(페이지를 내지 않는다). 다시 시작해도 내용 주소 blob이라 중복은 없다.
 *   <li>선택 리소스(400·403·404 — 보드·필터·대시보드·컴포넌트·버전·스프린트·이슈·자식·원격 링크·첨부)는 거부(unsupported_item)로 남기고
 *       계속한다. 필드·프로젝트·이슈 검색은 필수다. 작업 종류별 정책(모양·필수 여부·경로·봉투 키)은 {@link WorkKind} 한 곳에 있다.
 *   <li>작업 JSON은 TS 객체처럼 다룬다: 이어 읽기는 작업을 복사해 start·cursor만 바꾼다(키 순서 그대로) — 체크포인트 값이 TS와 같다. 저장이
 *       jsonb라 키 순서는 남지 않는다.
 *   <li>원본 버전은 TS `JSON.stringify(값)`의 sha256({@link JsJson}), 첨부는 바이트 sha256(blob).
 * </ul>
 */
public final class JiraCollection {
  static final int MAX_CONCURRENT_READS = 4;
  static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;
  static final String UNAVAILABLE =
      "Jira resource unavailable to this account or API; coverage is incomplete";
  static final String ATTACHMENT_TOO_LARGE =
      "Attachment exceeds the 50 MiB raw capture limit; original not downloaded";

  private static final Set<Integer> OPTIONAL_UNAVAILABLE = Set.of(400, 403, 404);
  private static final Set<Integer> ATTACHMENT_UNAVAILABLE = Set.of(403, 404);
  private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
  private static final BigDecimal MAX_SAFE = BigDecimal.valueOf(JsJson.MAX_SAFE_INTEGER);

  /** 읽을 수 없는 자원의 사유 — 거부 메시지(TS와 같은 문구). */
  enum Unavailable {
    /** 선택 목록·이슈·자식·원격 링크의 400·403·404, 첨부의 403·404. */
    RESOURCE(UNAVAILABLE),
    /** 크기를 미리 아는 첨부가 캡처 상한을 넘는다(내려받지 않는다). */
    ATTACHMENT_TOO_LARGE(JiraCollection.ATTACHMENT_TOO_LARGE);

    final String message;

    Unavailable(String message) {
      this.message = message;
    }
  }

  /** 이 작업은 이 계정·API로 읽을 수 없다 — 거부로 남기고 계속한다. */
  static final class UnavailableResource extends RuntimeException {
    final Unavailable reason;

    UnavailableResource(Unavailable reason) {
      super(reason.message);
      this.reason = reason;
    }
  }

  /**
   * 이슈의 자식 목록(TS ISSUE_CHILDREN): 끝점 이름, 이슈 원본에 넣는 키, 끝점 페이지의 항목 키, 내장 목록의 위치(fields 또는 이슈)와 그 항목
   * 키. 변경 이력만 이슈 최상위에 `histories`로 내장된다.
   */
  private enum IssueChild {
    COMMENTS("comment", "comments", "comments", Holder.FIELDS, "comments"),
    CHANGELOG("changelog", "changelog", "values", Holder.ISSUE, "histories"),
    WORKLOGS("worklog", "worklogs", "worklogs", Holder.FIELDS, "worklogs");

    enum Holder {
      FIELDS,
      ISSUE
    }

    final String endpoint;
    final String issueKey;
    final String pageKey;
    final Holder embeddedIn;
    final String embeddedListKey;

    IssueChild(
        String endpoint,
        String issueKey,
        String pageKey,
        Holder embeddedIn,
        String embeddedListKey) {
      this.endpoint = endpoint;
      this.issueKey = issueKey;
      this.pageKey = pageKey;
      this.embeddedIn = embeddedIn;
      this.embeddedListKey = embeddedListKey;
    }
  }

  /** 작업 하나의 결과: 원본 변경과 큐 앞에 넣을 다음 작업. */
  private record Collected(List<RawMutation> mutations, List<ObjectNode> next) {}

  /** 배치 안 작업 하나의 끝(TS Promise.allSettled의 한 칸): 결과 또는 실패. */
  private sealed interface Outcome {
    record Done(ObjectNode work, Collected collected) implements Outcome {}

    record Failed(ObjectNode work, RuntimeException failure) implements Outcome {}
  }

  private final CollectionInput input;
  private final JiraConfiguration config;
  private final String token;
  private final JiraApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public JiraCollection(
      CollectionInput input,
      JiraConfiguration config,
      String token,
      JiraApiClient client,
      ObjectMapper json,
      Clock clock) {
    this.input = input;
    this.config = config;
    this.token = token;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  public void collect(PageSink sink) {
    List<ObjectNode> pending = new ArrayList<>();
    Set<String> downloaded = new LinkedHashSet<>();
    resume(pending, downloaded);
    while (!pending.isEmpty()) {
      if (input.cancel().isCancelled())
        throw new CallFailureException(
            "jira.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
      List<ObjectNode> batch = nextBatch(pending);
      // 빈 배치는 큐를 줄이지 못해 끝없이 페이지를 낸다 — 결함이므로 바로 멈춘다
      if (batch.isEmpty()) throw new IllegalStateException("empty Jira batch");
      Instant observedAt = clock.instant();
      List<Outcome> outcomes = run(batch, observedAt);
      List<RawMutation> mutations = new ArrayList<>();
      List<ObjectNode> next = new ArrayList<>();
      List<RejectedItem> rejections = new ArrayList<>();
      for (Outcome outcome : outcomes) {
        switch (outcome) {
          case Outcome.Done done -> {
            mutations.addAll(done.collected().mutations());
            for (ObjectNode work : done.collected().next())
              if (WorkKind.of(work) != WorkKind.ATTACHMENT
                  || !downloaded.contains(text(work, "id"))) next.add(work);
            if (WorkKind.of(done.work()) == WorkKind.ATTACHMENT)
              downloaded.add(text(done.work(), "id"));
          }
          case Outcome.Failed failed -> {
            if (!(failed.failure() instanceof UnavailableResource unavailable))
              throw failed.failure();
            rejections.add(rejection(failed.work(), unavailable.reason, observedAt));
          }
        }
      }
      List<ObjectNode> remaining = new ArrayList<>(next);
      remaining.addAll(pending.subList(batch.size(), pending.size()));
      pending = remaining;
      sink.accept(new CollectionPage(mutations, rejections, checkpoint(pending, downloaded)));
    }
  }

  // ---- 작업 큐·체크포인트 ----

  private List<ObjectNode> nextBatch(List<ObjectNode> pending) {
    ObjectNode first = pending.getFirst();
    WorkKind kind = WorkKind.of(first);
    if (!kind.batched()) return List.of(first);
    List<ObjectNode> batch = new ArrayList<>();
    for (ObjectNode work : pending) {
      if (WorkKind.of(work) != kind || batch.size() == MAX_CONCURRENT_READS) break;
      batch.add(work);
    }
    return batch;
  }

  /** TS resume: 체크포인트가 없거나 큐가 비었으면 루트부터. 다른 사이트·형식 위반은 요청 없이 실패한다. */
  private void resume(List<ObjectNode> pending, Set<String> downloaded) {
    Optional<Checkpoint> stored = input.checkpoint();
    if (stored.isEmpty()) {
      roots(pending);
      return;
    }
    JsonNode value = JiraPages.object(json.readTree(stored.get().valueJson()));
    JsonNode site = value.get("siteUrl");
    JsonNode list = value.get("pending");
    if (stored.get().schemaVersion() != 1
        || site == null
        || !site.isString()
        || !site.asString().equals(config.siteUrl())
        || list == null
        || !list.isArray()) throw new JiraProtocolException("Invalid Jira checkpoint");
    JsonNode history = value.get("downloadedAttachments");
    if (history != null) {
      if (!history.isArray()) throw new JiraProtocolException("Invalid Jira attachment checkpoint");
      for (JsonNode id : history)
        if (!id.isString()) throw new JiraProtocolException("Invalid Jira attachment checkpoint");
    }
    for (JsonNode entry : list) pending.add(validWork(entry));
    if (pending.isEmpty()) roots(pending);
    if (history != null) history.forEach(id -> downloaded.add(id.asString()));
  }

  private ObjectNode validWork(JsonNode entry) {
    ObjectNode work = (ObjectNode) JiraPages.object(entry);
    JsonNode kind = work.get("kind");
    JsonNode start = work.get("start");
    Optional<WorkKind> known =
        kind == null || !kind.isString() ? Optional.empty() : WorkKind.fromWire(kind.asString());
    boolean badStart = start != null && !(isSafeInteger(start) && start.asDouble() >= 0);
    boolean badText =
        List.of("id", "key", "cursor").stream()
            .map(work::get)
            .anyMatch(v -> v != null && (!v.isString() || v.asString().isEmpty()));
    if (known.isEmpty() || badStart || badText)
      throw new JiraProtocolException("Invalid Jira checkpoint work");
    JsonNode id = work.get("id");
    if (known.get().shape != WorkKind.Shape.ROOT_LISTING && (id == null || !id.isString()))
      throw new JiraProtocolException("Missing Jira checkpoint identity");
    return work.deepCopy();
  }

  /** JS {@code Number.isSafeInteger}: 소수점 표기라도 정수 값(1.0·1e3)이면 받고, |값| ≤ 2^53 − 1. */
  private static boolean isSafeInteger(JsonNode value) {
    if (!value.isNumber()) return false;
    BigDecimal number = value.decimalValue();
    return number.stripTrailingZeros().scale() <= 0 && number.abs().compareTo(MAX_SAFE) <= 0;
  }

  private void roots(List<ObjectNode> pending) {
    for (WorkKind kind : WorkKind.roots()) pending.add(work(kind));
  }

  private Checkpoint checkpoint(List<ObjectNode> pending, Set<String> downloaded) {
    ObjectNode value = json.createObjectNode().put("siteUrl", config.siteUrl());
    ArrayNode queue = value.putArray("pending");
    pending.forEach(queue::add);
    ArrayNode history = value.putArray("downloadedAttachments");
    downloaded.forEach(history::add);
    return new Checkpoint(1, JsJson.stringify(value));
  }

  private RejectedItem rejection(ObjectNode work, Unavailable reason, Instant observedAt) {
    ObjectNode locator =
        json.createObjectNode()
            .put("kind", WorkKind.of(work).wire)
            .put("id", work.has("id") ? text(work, "id") : "site");
    if (work.has("start")) locator.set("start", work.get("start"));
    else locator.put("start", 0);
    return new RejectedItem(
        RejectedItem.FailureKind.UNSUPPORTED_ITEM,
        reason.message,
        Optional.of(JsJson.stringify(locator)),
        observedAt,
        Optional.empty());
  }

  // ---- 배치 실행 ----

  /** 배치의 작업을 동시에(가상 스레드) 돌리고 모두 끝난 뒤 배치 순서대로 돌려준다(TS Promise.allSettled). */
  private List<Outcome> run(List<ObjectNode> batch, Instant observedAt) {
    if (batch.size() == 1) return List.of(attempt(batch.getFirst(), observedAt));
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Outcome>> futures =
          batch.stream().map(work -> threads.submit(() -> attempt(work, observedAt))).toList();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> future : futures) outcomes.add(await(future));
      return outcomes;
    }
  }

  private static Outcome await(Future<Outcome> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CallFailureException(
          "jira.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "interrupted"));
    } catch (ExecutionException e) {
      throw new IllegalStateException("Jira task did not record its outcome", e.getCause());
    }
  }

  private Outcome attempt(ObjectNode work, Instant observedAt) {
    try {
      return new Outcome.Done(work, collectTask(work, observedAt));
    } catch (RuntimeException e) {
      return new Outcome.Failed(work, e);
    }
  }

  private Collected collectTask(ObjectNode work, Instant observedAt) {
    WorkKind kind = WorkKind.of(work);
    return switch (kind.shape) {
      case ATTACHMENT -> collectAttachment(work, observedAt);
      case ISSUE -> collectIssue(work, observedAt);
      case ROOT_LISTING, CHILD_LISTING -> collectListing(work, kind, observedAt);
    };
  }

  // ---- 작업 종류별 ----

  private Collected collectAttachment(ObjectNode work, Instant observedAt) {
    JsonNode size = work.get("size");
    if (size != null && size.isNumber() && size.asDouble() > MAX_ATTACHMENT_BYTES)
      throw new UnavailableResource(Unavailable.ATTACHMENT_TOO_LARGE);
    String id = text(work, "id");
    RawContent content =
        input
            .calls()
            .execute(
                "jira.attachment",
                timeout -> {
                  JiraApiClient.Attachment a =
                      client.attachment(config, token, id, timeout, MAX_ATTACHMENT_BYTES);
                  return input.content().bytes(a.mediaType(), a.bytes());
                },
                e -> {
                  if (e instanceof JiraApiException api
                      && ATTACHMENT_UNAVAILABLE.contains(api.status()))
                    throw new UnavailableResource(Unavailable.RESOURCE);
                  return JiraErrors.classify(e);
                },
                input.cancel());
    String sourceVersion =
        content instanceof RawContent.Blob blob ? blob.sha256() : observedAt.toString();
    ObjectNode metadata =
        json.createObjectNode()
            .put("issueKey", work.has("key") ? text(work, "key") : "")
            .put("extraction", "not_extracted");
    RawItem item =
        new RawItem(
            "attachment:" + id,
            "jira.attachment",
            sourceVersion,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            observedAt,
            JsJson.stringify(metadata),
            Optional.of(content));
    return new Collected(List.of(new RawMutation.Upsert(item)), List.of());
  }

  private Collected collectIssue(ObjectNode work, Instant observedAt) {
    String id = JiraApiClient.segment(work.has("id") ? text(work, "id") : "");
    ObjectNode issue =
        (ObjectNode)
            JiraPages.object(
                requestJson(
                    "/rest/api/3/issue/" + id + "?fields=*all&expand=names,changelog",
                    WorkKind.Availability.OPTIONAL));
    if (!jsString(issue.get("id")).equals(work.has("id") ? text(work, "id") : "undefined"))
      throw new JiraProtocolException("Jira returned wrong issue identity");
    JsonNode fields = JiraPages.object(issue.get("fields"));
    // 자식 목록(댓글·변경 이력·작업 기록)은 끝점마다 페이지가 따로다 — 완전한 목록으로 이슈와 함께 통째로 바꿔, 지운 댓글이 검색에 남지 않게
    for (IssueChild child : IssueChild.values()) {
      JsonNode embedded =
          child.embeddedIn == IssueChild.Holder.ISSUE
              ? issue.get(child.endpoint)
              : fields.get(child.endpoint);
      List<JsonNode> entries =
          JiraPages.completeEmbedded(embedded, child.embeddedListKey)
              .orElseGet(
                  () ->
                      readAllChildren(
                          "/rest/api/3/issue/" + id + "/" + child.endpoint, child.pageKey));
      ArrayNode array = json.createArrayNode();
      entries.forEach(array::add);
      issue.set(child.issueKey, array);
    }
    issue.set(
        "remoteLinks",
        requestJson("/rest/api/3/issue/" + id + "/remotelink", WorkKind.Availability.OPTIONAL));
    List<ObjectNode> next = new ArrayList<>();
    JsonNode attachments = fields.get("attachment");
    if (config.downloadAttachments() && attachments != null && attachments.isArray()) {
      for (JsonNode attachment : attachments) {
        ObjectNode task =
            work(WorkKind.ATTACHMENT)
                .put("id", JiraPages.identity(JiraPages.object(attachment)))
                .put("key", jsString(issue.get("key")));
        JsonNode size = attachment.get("size");
        if (size != null && size.isNumber()) task.set("size", size);
        next.add(task);
      }
    }
    return new Collected(List.of(upsert(WorkKind.ISSUE, issue, observedAt)), next);
  }

  private Collected collectListing(ObjectNode work, WorkKind kind, Instant observedAt) {
    String id = JiraApiClient.segment(work.has("id") ? text(work, "id") : "");
    long start = work.has("start") ? work.get("start").asLong() : 0;
    Map<String, String> query = new LinkedHashMap<>();
    query.put("maxResults", "50");
    query.put("startAt", String.valueOf(start));
    kind.expand.ifPresent(e -> query.put("expand", e));
    if (kind == WorkKind.ISSUES) {
      query.remove("startAt");
      // 숫자 프로젝트 id만 — JQL 주입을 막고 검색 범위를 묶는다
      if (!NUMERIC.matcher(text(work, "id")).matches())
        throw new JiraProtocolException("Invalid Jira project ID");
      query.put("jql", "project = " + text(work, "id") + " ORDER BY id ASC");
      query.put("fields", "summary");
      if (work.has("cursor")) query.put("nextPageToken", text(work, "cursor"));
    }
    JsonNode data = requestJson(kind.path(id) + "?" + form(query), kind.availability);
    JiraPages.Page page =
        kind == WorkKind.ISSUES
            ? JiraPages.search(
                data, work.has("cursor") ? Optional.of(text(work, "cursor")) : Optional.empty())
            : JiraPages.listing(data, kind.envelopeKey(), start);
    List<ObjectNode> next = new ArrayList<>();
    List<RawMutation> mutations = new ArrayList<>();
    for (JsonNode entry : page.entries()) {
      String entryId = JiraPages.identity(entry);
      if (kind == WorkKind.ISSUES) {
        next.add(work(WorkKind.ISSUE).put("id", entryId));
        continue;
      }
      mutations.add(upsert(kind, (ObjectNode) entry, observedAt));
      if (kind == WorkKind.PROJECTS)
        for (WorkKind child : List.of(WorkKind.COMPONENTS, WorkKind.VERSIONS, WorkKind.ISSUES))
          next.add(work(child).put("id", entryId));
      JsonNode type = entry.get("type");
      if (kind == WorkKind.BOARDS
          && type != null
          && (type.asString("").equals("scrum") || type.asString("").equals("simple")))
        next.add(work(WorkKind.SPRINTS).put("id", entryId));
    }
    page.continuation()
        .ifPresent(
            c -> {
              ObjectNode continued = work.deepCopy();
              switch (c) {
                case JiraPages.Continuation.Offset o -> continued.put("start", o.start());
                case JiraPages.Continuation.Cursor cur -> continued.put("cursor", cur.token());
              }
              next.add(continued);
            });
    return new Collected(mutations, next);
  }

  private List<JsonNode> readAllChildren(String path, String key) {
    List<JsonNode> result = new ArrayList<>();
    long start = 0;
    while (true) {
      JsonNode data =
          requestJson(path + "?maxResults=100&startAt=" + start, WorkKind.Availability.OPTIONAL);
      JiraPages.Page page = JiraPages.listing(data, key, start);
      result.addAll(page.entries());
      if (page.continuation().isEmpty()) return result;
      start =
          switch (page.continuation().get()) {
            case JiraPages.Continuation.Offset o -> o.start();
            case JiraPages.Continuation.Cursor c ->
                throw new IllegalStateException("offset listing continued with a cursor");
          };
    }
  }

  private RawMutation upsert(WorkKind kind, ObjectNode value, Instant observedAt) {
    boolean issue = kind == WorkKind.ISSUE;
    String resource = kind.resource.orElseThrow();
    JsonNode fields = issue ? JiraPages.object(value.get("fields")) : value;
    String uri =
        issue
            ? config.siteUrl() + "/browse/" + JiraApiClient.segment(jsString(value.get("key")))
            : config.siteUrl();
    String stringified = JsJson.stringify(value);
    ObjectNode metadata =
        json.createObjectNode().put("url", uri).put("attachmentExtraction", "not_extracted");
    RawItem item =
        new RawItem(
            resource + ":" + JiraPages.identity(value),
            "jira." + resource,
            Text.sha256Hex(stringified),
            Optional.empty(),
            stringField(fields, "created"),
            stringField(fields, "updated"),
            observedAt,
            JsJson.stringify(metadata),
            Optional.of(input.content().json("application/json", stringified)));
    return new RawMutation.Upsert(item);
  }

  private JsonNode requestJson(String path, WorkKind.Availability availability) {
    return input
        .calls()
        .execute(
            "jira.read",
            timeout -> client.json(config, token, path, timeout),
            e -> {
              if (availability == WorkKind.Availability.OPTIONAL
                  && e instanceof JiraApiException api
                  && OPTIONAL_UNAVAILABLE.contains(api.status()))
                throw new UnavailableResource(Unavailable.RESOURCE);
              return JiraErrors.classify(e);
            },
            input.cancel());
  }

  // ---- JSON 도우미 ----

  private ObjectNode work(WorkKind kind) {
    return json.createObjectNode().put("kind", kind.wire);
  }

  private static String text(JsonNode work, String field) {
    return work.get(field).asString();
  }

  /** TS String(값): 없으면 "undefined", 수·문자열은 그 표기. */
  private static String jsString(JsonNode value) {
    if (value == null) return "undefined";
    if (value.isNumber()) return JsJson.stringify(value);
    return value.asString();
  }

  private static Optional<String> stringField(JsonNode fields, String name) {
    JsonNode v = fields.get(name);
    return v != null && v.isString() ? Optional.of(v.asString()) : Optional.empty();
  }

  /** TS URLSearchParams.toString(): 넣은 순서, form 인코딩(공백 +). */
  private static String form(Map<String, String> query) {
    return query.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
