package io.scos.connector.jira.internal;

import io.scos.connector.jira.internal.JiraBatch.Collected;
import io.scos.connector.jira.internal.JiraBatch.Outcome;
import io.scos.engine.support.call.CursorTrail;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira run 하나가 소유하는 걷기. 작업 큐({@link JiraQueue})의 앞에서 배치를 꺼내 함께 돌리고({@link JiraBatch}), 배치마다 페이지 하나를
 * 낸다 — 런타임이 페이지를 커밋하므로 체크포인트는 그 페이지까지 확정된 큐다.
 *
 * <ul>
 *   <li>배치 안 한 작업이 일시 장애로 끝나면 성공한 형제도 확정하지 않는다(페이지를 내지 않는다). 다시 시작해도 내용 주소 blob이라 중복은 없다.
 *   <li>선택 리소스(400·403·404 — 보드·필터·대시보드·컴포넌트·버전·스프린트·이슈·자식·원격 링크·첨부)는 거부(unsupported_item)로 남기고
 *       계속한다. 필드·프로젝트·이슈 검색은 필수다. 작업 종류별 정책(모양·필수 여부·경로·봉투 키)은 {@link WorkKind} 한 곳에 있다.
 *   <li>원본 버전은 직렬화한 값({@link JsJson})의 sha256, 첨부는 바이트 sha256(blob).
 * </ul>
 */
public final class JiraCollection {
  static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;
  static final String UNAVAILABLE =
      "Jira resource unavailable to this account or API; coverage is incomplete";
  static final String ATTACHMENT_TOO_LARGE =
      "Attachment exceeds the 50 MiB raw capture limit; original not downloaded";

  private static final Set<Integer> OPTIONAL_UNAVAILABLE = Set.of(400, 403, 404);
  private static final Set<Integer> ATTACHMENT_UNAVAILABLE = Set.of(403, 404);
  private static final Pattern NUMERIC = Pattern.compile("^\\d+$");

  /** 읽을 수 없는 자원의 사유 — 거부 메시지. */
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
   * 이슈의 자식 목록: 끝점 이름, 이슈 원본에 넣는 키, 끝점 페이지의 항목 키, 내장 목록의 위치(fields 또는 이슈)와 그 항목 키. 변경 이력만 이슈 최상위에
   * `histories`로 내장된다.
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

  private final CollectionInput input;
  private final JiraConfiguration config;
  private final String token;
  private final JiraApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  /**
   * 프로젝트마다 이슈 검색 걷기의 자취: (프로젝트, 다음 커서) → 그 걷기에서 이미 요청한 커서. 이어지는 페이지는 작업 대기열(동시 배치)을 거쳐 오므로 다음 커서로
   * 찾아 잇는다. 체크포인트에서 재개한 걷기는 그 커서부터 새 자취다.
   */
  private final Map<SearchWalk, CursorTrail> searchTrails = new ConcurrentHashMap<>();

  private record SearchWalk(String project, String cursor) {}

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
    JiraQueue queue = JiraQueue.resume(json, input.checkpoint(), config.siteUrl());
    while (!queue.isEmpty()) {
      if (input.cancel().isCancelled())
        throw new CallFailureException(
            "jira.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
      List<JiraWork> batch = queue.nextBatch();
      // 빈 배치는 큐를 줄이지 못해 끝없이 페이지를 낸다 — 결함이므로 바로 멈춘다
      if (batch.isEmpty()) throw new IllegalStateException("empty Jira batch");
      Instant observedAt = clock.instant();
      List<Outcome> outcomes = new JiraBatch(work -> collectTask(work, observedAt)).run(batch);
      List<RawMutation> mutations = new ArrayList<>();
      List<JiraWork> next = new ArrayList<>();
      List<RejectedItem> rejections = new ArrayList<>();
      for (Outcome outcome : outcomes) {
        switch (outcome) {
          case Outcome.Done done -> {
            mutations.addAll(done.collected().mutations());
            for (JiraWork work : done.collected().next())
              if (queue.worthQueueing(work)) next.add(work);
            if (done.work().kind() == WorkKind.ATTACHMENT) queue.downloaded(done.work());
          }
          case Outcome.Failed failed -> {
            if (!(failed.failure() instanceof UnavailableResource unavailable))
              throw failed.failure();
            rejections.add(rejection(failed.work(), unavailable.reason, observedAt));
          }
        }
      }
      queue.advance(batch.size(), next);
      sink.accept(new CollectionPage(mutations, rejections, queue.checkpoint()));
    }
  }

  private RejectedItem rejection(JiraWork work, Unavailable reason, Instant observedAt) {
    ObjectNode locator =
        json.createObjectNode().put("kind", work.kind().wire).put("id", work.id().orElse("site"));
    work.startValue().ifPresentOrElse(s -> locator.set("start", s), () -> locator.put("start", 0));
    return new RejectedItem(
        RejectedItem.FailureKind.UNSUPPORTED_ITEM,
        reason.message,
        Optional.of(JsJson.stringify(locator)),
        observedAt,
        Optional.empty());
  }

  private Collected collectTask(JiraWork work, Instant observedAt) {
    WorkKind kind = work.kind();
    return switch (kind.shape) {
      case ATTACHMENT -> collectAttachment(work, observedAt);
      case ISSUE -> collectIssue(work, observedAt);
      case ROOT_LISTING, CHILD_LISTING ->
          kind == WorkKind.ISSUES
              ? collectIssueSearch(work)
              : collectListing(work, kind, observedAt);
    };
  }

  // ---- 작업 종류별 ----

  private Collected collectAttachment(JiraWork work, Instant observedAt) {
    if (work.size().filter(size -> size > MAX_ATTACHMENT_BYTES).isPresent())
      throw new UnavailableResource(Unavailable.ATTACHMENT_TOO_LARGE);
    String id = work.id().orElseThrow();
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
            .put("issueKey", work.key().orElse(""))
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

  private Collected collectIssue(JiraWork work, Instant observedAt) {
    String id = JiraApiClient.segment(work.id().orElse(""));
    ObjectNode issue =
        (ObjectNode)
            JiraPages.object(
                requestJson(
                    "/rest/api/3/issue/" + id + "?fields=*all&expand=names,changelog",
                    WorkKind.Availability.OPTIONAL));
    if (!jsString(issue.get("id")).equals(work.id().orElse("undefined")))
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
    return new Collected(
        List.of(upsert(WorkKind.ISSUE, issue, observedAt)), attachmentWork(issue, fields));
  }

  /** 첨부 내려받기가 켜져 있으면 이슈의 첨부마다 작업(크기를 알면 함께). */
  private List<JiraWork> attachmentWork(ObjectNode issue, JsonNode fields) {
    List<JiraWork> next = new ArrayList<>();
    JsonNode attachments = fields.get("attachment");
    if (!config.downloadAttachments() || attachments == null || !attachments.isArray()) return next;
    for (JsonNode attachment : attachments) {
      JiraWork task =
          JiraWork.of(json, WorkKind.ATTACHMENT)
              .withId(JiraPages.identity(JiraPages.object(attachment)))
              .withKey(jsString(issue.get("key")));
      JsonNode size = attachment.get("size");
      if (size != null && size.isNumber()) task = task.withSize(size);
      next.add(task);
    }
    return next;
  }

  /** 프로젝트의 이슈 검색(커서 페이지): 항목마다 이슈 작업을 낸다. 숫자 프로젝트 id만 — JQL 주입을 막고 검색 범위를 묶는다. */
  private Collected collectIssueSearch(JiraWork work) {
    String project = work.id().orElseThrow();
    if (!NUMERIC.matcher(project).matches())
      throw new JiraProtocolException("Invalid Jira project ID");
    Map<String, String> query = new LinkedHashMap<>();
    query.put("maxResults", "50");
    WorkKind.ISSUES.expand.ifPresent(e -> query.put("expand", e));
    query.put("jql", "project = " + project + " ORDER BY id ASC");
    query.put("fields", "summary");
    work.cursor().ifPresent(c -> query.put("nextPageToken", c));
    JsonNode data =
        requestJson(
            WorkKind.ISSUES.path(JiraApiClient.segment(project)) + "?" + form(query),
            WorkKind.ISSUES.availability);
    JiraPages.Page page = JiraPages.search(data);
    advanceSearch(project, work.cursor(), page);
    List<JiraWork> next = new ArrayList<>();
    for (JsonNode entry : page.entries())
      next.add(JiraWork.of(json, WorkKind.ISSUE).withId(JiraPages.identity(entry)));
    page.continuation().ifPresent(c -> next.add(work.continuedAt(c)));
    return new Collected(List.of(), next);
  }

  /** 다음 커서가 이 프로젝트 검색 걷기에서 이미 요청한 커서면(A → A·A → B → A) 위반(메시지 끝에 걷기 설명). 아니면 그 커서로 자취를 잇는다. */
  private void advanceSearch(String project, Optional<String> cursor, JiraPages.Page page) {
    CursorTrail trail =
        cursor
            .map(c -> searchTrails.remove(new SearchWalk(project, c)))
            .orElseGet(CursorTrail::new);
    trail.request(cursor);
    if (page.continuation().orElse(null) instanceof JiraPages.Continuation.Cursor next) {
      if (trail.revisits(Optional.of(next.token())))
        throw new JiraProtocolException(
            "Jira search did not advance ("
                + trail.cycle("search:" + project, Optional.of(next.token()))
                + ")");
      searchTrails.put(new SearchWalk(project, next.token()), trail);
    }
  }

  /** 오프셋 목록 한 페이지: 항목마다 원본과 그 항목의 자식 작업. */
  private Collected collectListing(JiraWork work, WorkKind kind, Instant observedAt) {
    String id = JiraApiClient.segment(work.id().orElse(""));
    long start = work.start();
    Map<String, String> query = new LinkedHashMap<>();
    query.put("maxResults", "50");
    query.put("startAt", String.valueOf(start));
    kind.expand.ifPresent(e -> query.put("expand", e));
    JsonNode data = requestJson(kind.path(id) + "?" + form(query), kind.availability);
    JiraPages.Page page = JiraPages.listing(data, kind.envelopeKey(), start);
    List<JiraWork> next = new ArrayList<>();
    List<RawMutation> mutations = new ArrayList<>();
    for (JsonNode entry : page.entries()) {
      String entryId = JiraPages.identity(entry);
      mutations.add(upsert(kind, (ObjectNode) entry, observedAt));
      for (WorkKind child : kind.childKindsOf(entry))
        next.add(JiraWork.of(json, child).withId(entryId));
    }
    page.continuation().ifPresent(c -> next.add(work.continuedAt(c)));
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

  /** JS String(값): 없으면 "undefined", 수·문자열은 그 표기. */
  private static String jsString(JsonNode value) {
    if (value == null) return "undefined";
    if (value.isNumber()) return JsJson.stringify(value);
    return value.asString();
  }

  private static Optional<String> stringField(JsonNode fields, String name) {
    JsonNode v = fields.get(name);
    return v != null && v.isString() ? Optional.of(v.asString()) : Optional.empty();
  }

  /** URLSearchParams 순서·form 인코딩(공백 +). */
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
