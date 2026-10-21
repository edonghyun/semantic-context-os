package io.scos.connector.tiro;

import static io.scos.connector.tiro.internal.TiroResponses.invalid;
import static io.scos.connector.tiro.internal.TiroResponses.object;

import io.scos.connector.tiro.internal.CursorWalk;
import io.scos.connector.tiro.internal.TiroApiClient;
import io.scos.connector.tiro.internal.TiroApiException;
import io.scos.connector.tiro.internal.TiroCheckpoint;
import io.scos.connector.tiro.internal.TiroConfiguration;
import io.scos.connector.tiro.internal.TiroResponses;
import io.scos.engine.support.text.JsJson;
import io.scos.engine.support.text.Text;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.Checkpoint;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.DeletionDetection;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawContent;
import io.scos.spi.connector.RawItem;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceConfig;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tiro 회의록 어댑터(TS tiro-adapter.ts 1:1, ADR-0022): 워크스페이스 노트 목록(폴더 또는 전체) → 노트마다 상세·전사 문단(전
 * 페이지)·요약·회의 문서를 모두 읽은 뒤 `note:<guid>` 원본 하나(`tiro.note`)로 저장한다. 음성·공유 링크는 받지 않는다.
 *
 * <ul>
 *   <li>항상 complete_snapshot: 매 실행이 목록 첫 페이지부터 끝까지 걷고(저장된 커서로 재개하지 않는다), 성공하면 이번에 보지 못한 노트를 런타임이
 *       tombstone으로 바꾼다.
 *   <li>노트 상세의 403·404는 그 노트의 삭제다. 문단·요약·문서 경로의 403·404는 그 종류만 비우고 `unavailableResources`에 남긴다. 목록의
 *       403·404는 run 실패다(키·설정 오류와 접근 회수를 구분할 수 없다).
 *   <li>sourceVersion = sha256(JSON.stringify(본문)) — 전사·요약만 바뀌어도 새 버전이다. sourceUpdatedAt은 노트·요약·문서
 *       중 가장 늦은 updatedAt.
 *   <li>잠긴 문단은 거부가 아니다(전부 목록에 있고 본문에서만 빠진다) — 거부 항목이 있으면 정리가 막히므로 메타데이터 `lockedParagraphs`로만 남긴다.
 * </ul>
 */
public final class TiroAdapter implements SourceAdapter {
  public static final String KIND = "tiro";
  static final String RESOURCE_TYPE = "tiro.note";
  static final int LIST_PAGE_SIZE = 20;
  static final int PARAGRAPH_PAGE_SIZE = 100;

  /** 2^53 − 1: JS Number.isSafeInteger 상한(문서 id 검사). */
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]+$");
  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int CLIENT_ERROR_FROM = 400;
  private static final int SERVER_ERROR_FROM = 500;

  /** 노트 아래 자원 종류(경로 조각이자 `unavailableResources` 값). */
  enum Resource {
    PARAGRAPHS("paragraphs"),
    SUMMARIES("summaries"),
    DOCUMENTS("documents");

    final String path;

    Resource(String path) {
      this.path = path;
    }
  }

  /** 읽는 경로의 범위: 노트 아래 경로의 403·404만 "그 노트(또는 그 종류)를 읽을 수 없다"로 본다. */
  private enum Scope {
    WORKSPACE,
    NOTE
  }

  /** 노트 경로의 403·404 — 분류 대신 호출자에게 알린다(재시도·run 실패 아님). */
  private static final class UnavailableNote extends RuntimeException {
    UnavailableNote() {
      super("Tiro note resource unavailable", null, false, false);
    }
  }

  private final CredentialResolver credentials;
  private final TiroApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public TiroAdapter(
      CredentialResolver credentials, TiroApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public void validateConfig(SourceConfig config) {
    TiroConfiguration.read(json, config);
  }

  /** 재개하지 않으므로 모든 실행이 완전한 목록이다. */
  @Override
  public CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint) {
    return CollectionCoverage.COMPLETE_SNAPSHOT;
  }

  @Override
  public DeletionDetection deletionDetection(SourceConfig config) {
    return DeletionDetection.COMPLETE_SNAPSHOT;
  }

  /** TS classifyTiroError. 응답 본문·키를 싣지 않는다. */
  static CallFailure classify(Exception error) {
    if (!(error instanceof TiroApiException api))
      return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, "Tiro transport failed");
    int status = api.status();
    String message = api.getMessage();
    if (status == UNAUTHORIZED)
      return CallFailure.of(CallFailure.Kind.AUTHENTICATION_FAILED, message);
    if (status == FORBIDDEN || status == NOT_FOUND)
      return CallFailure.of(CallFailure.Kind.PERMISSION_DENIED, message);
    if (status == TOO_MANY_REQUESTS) return CallFailure.rateLimited(message, api.retryAfter());
    if (status >= CLIENT_ERROR_FROM && status < SERVER_ERROR_FROM)
      return CallFailure.of(CallFailure.Kind.INVALID_PROVIDER_RESPONSE, message);
    return CallFailure.of(CallFailure.Kind.DEPENDENCY_UNAVAILABLE, message);
  }

  @Override
  public void collect(CollectionInput input, PageSink sink) {
    TiroConfiguration config = TiroConfiguration.read(json, input.source().config());
    String scope = config.scope();
    TiroCheckpoint.requireSameScope(json, input.checkpoint(), scope);
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    String listPath = "/v1/external/workspaces/" + config.workspaceGuid() + "/notes";
    CursorWalk walk = new CursorWalk(LIST_PAGE_SIZE, "Tiro pagination did not advance");
    Optional<String> cursor = Optional.empty();
    do {
      // 취소는 실행기가 호출마다 먼저 본다(TS throwIfAborted와 같은 지점)
      Map<String, String> query = walk.query(cursor);
      if (config.selection() instanceof TiroConfiguration.Selection.Folder folder)
        query.put("folderId", folder.folderId());
      TiroResponses.CursorPage page =
          TiroResponses.cursorPage(read(input, token, listPath, query, Scope.WORKSPACE));
      walk.received(page.nextCursor());
      List<RawMutation> mutations = new ArrayList<>();
      for (ObjectNode listed : page.entries())
        mutations.add(note(input, token, config, guid(listed)));
      cursor = page.nextCursor();
      sink.accept(new CollectionPage(mutations, List.of(), TiroCheckpoint.write(scope, cursor)));
    } while (cursor.isPresent());
  }

  private static String guid(ObjectNode listed) {
    JsonNode guid = listed.get("guid");
    if (guid == null || !guid.isString() || !ID.matcher(guid.asString()).matches())
      throw invalid("Invalid Tiro note identity");
    return guid.asString();
  }

  /** 노트 하나의 변경: 읽을 수 있으면 upsert, 상세가 403·404면 delete. */
  private RawMutation note(
      CollectionInput input, String token, TiroConfiguration config, String guid) {
    String externalId = "note:" + guid;
    String path = "/v1/external/notes/" + guid;
    ObjectNode note;
    try {
      note = object(read(input, token, path, Map.of(), Scope.NOTE));
    } catch (UnavailableNote e) {
      return new RawMutation.Delete(
          externalId, Optional.empty(), Optional.empty(), Optional.empty(), clock.instant());
    }
    if (!isStringEqual(note.get("guid"), guid)
        || !isStringEqual(note.get("workspaceGuid"), config.workspaceGuid()))
      throw invalid("Tiro returned a note outside the configured workspace");
    for (String key : List.of("title", "createdAt", "updatedAt", "webUrl"))
      if (!TiroResponses.isString(note.get(key))) throw invalid("Invalid Tiro note response");
    ArrayNode unavailable = json.createArrayNode();
    ArrayNode paragraphs =
        available(Resource.PARAGRAPHS, unavailable, () -> paragraphs(input, token, path));
    ArrayNode summaries =
        available(
            Resource.SUMMARIES,
            unavailable,
            () -> details(input, token, path, Resource.SUMMARIES, guid));
    ArrayNode documents =
        available(
            Resource.DOCUMENTS,
            unavailable,
            () -> details(input, token, path, Resource.DOCUMENTS, guid));
    ObjectNode value = json.createObjectNode();
    value.set("note", note);
    value.set("paragraphs", paragraphs);
    value.set("summaries", summaries);
    value.set("documents", documents);
    value.set("unavailableResources", unavailable);
    String valueJson = JsJson.stringify(value);
    int locked = 0;
    for (JsonNode p : paragraphs) if (p.get("locked").asBoolean()) locked++;
    List<ObjectNode> updated = new ArrayList<>();
    updated.add(note);
    summaries.forEach(s -> updated.add((ObjectNode) s));
    documents.forEach(d -> updated.add((ObjectNode) d));
    ObjectNode metadata = json.createObjectNode();
    metadata.put("url", note.get("webUrl").asString());
    metadata.put("workspaceGuid", config.workspaceGuid());
    metadata.put("lockedParagraphs", locked);
    metadata.set("unavailableResources", unavailable.deepCopy());
    RawContent content = input.content().json("application/json", valueJson);
    return new RawMutation.Upsert(
        new RawItem(
            externalId,
            RESOURCE_TYPE,
            Text.sha256Hex(valueJson),
            Optional.empty(),
            Optional.of(note.get("createdAt").asString()),
            Optional.of(TiroResponses.latestUpdate(updated)),
            clock.instant(),
            json.writeValueAsString(metadata),
            Optional.of(content)));
  }

  @FunctionalInterface
  private interface ResourceRead {
    ArrayNode read();
  }

  /** 자원 한 종류를 읽는다. 그 경로가 403·404면 비우고 unavailable에 종류를 더한다. */
  private ArrayNode available(Resource resource, ArrayNode unavailable, ResourceRead read) {
    try {
      return read.read();
    } catch (UnavailableNote e) {
      unavailable.add(resource.path);
      return json.createArrayNode();
    }
  }

  /** 전사 문단 전 페이지(커서가 같은 곳을 돌면 위반). */
  private ArrayNode paragraphs(CollectionInput input, String token, String notePath) {
    ArrayNode out = json.createArrayNode();
    CursorWalk walk =
        new CursorWalk(PARAGRAPH_PAGE_SIZE, "Tiro paragraph pagination did not advance");
    Optional<String> cursor = Optional.empty();
    do {
      Map<String, String> query = walk.query(cursor);
      TiroResponses.CursorPage page =
          TiroResponses.cursorPage(
              read(input, token, notePath + "/" + Resource.PARAGRAPHS.path, query, Scope.NOTE));
      // 받은 커서가 이미 요청한 커서면 다음 query()가 위반으로 멈춘다 — 문단은 페이지마다 내지 않으므로 TS와 결과가 같다
      for (ObjectNode paragraph : page.entries()) {
        TiroResponses.validateParagraph(paragraph);
        out.add(paragraph);
      }
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** 요약 또는 회의 문서: 목록(전부 실렸는지 totalSize로 확인) → 항목마다 상세. 잘린 문서·다른 노트의 문서·본문 없는 문서는 위반. */
  private ArrayNode details(
      CollectionInput input, String token, String notePath, Resource kind, String guid) {
    String path = notePath + "/" + kind.path;
    ObjectNode listing = object(read(input, token, path, Map.of(), Scope.NOTE));
    JsonNode content = listing.get("content");
    JsonNode totalSize = listing.get("totalSize");
    if (content == null
        || !content.isArray()
        || (totalSize != null && !(totalSize.isNumber() && totalSize.asDouble() == content.size())))
      throw invalid("Incomplete Tiro document listing");
    ArrayNode out = json.createArrayNode();
    for (JsonNode entry : content) {
      JsonNode id = object(entry).get("id");
      if (!isSafeNonNegativeInteger(id)) throw invalid("Invalid Tiro document identity");
      long number = (long) id.asDouble();
      ObjectNode detail = object(read(input, token, path + "/" + number, Map.of(), Scope.NOTE));
      JsonNode detailId = detail.get("id");
      JsonNode truncated = detail.get("truncated");
      if (detailId == null
          || !detailId.isNumber()
          || detailId.asDouble() != number
          || !isStringEqual(detail.get("noteGuid"), guid)
          || (truncated != null && truncated.isBoolean() && truncated.asBoolean()))
        throw invalid("Invalid Tiro document response");
      if (kind == Resource.DOCUMENTS
          ? !hasTextSections(detail)
          : !TiroResponses.isText(detail.get("content")))
        throw invalid("Invalid Tiro document content");
      out.add(detail);
    }
    return out;
  }

  private static boolean hasTextSections(ObjectNode document) {
    JsonNode sections = document.get("sections");
    if (sections == null || !sections.isArray()) return false;
    for (JsonNode section : sections)
      if (!TiroResponses.isText(object(section).get("content"))) return false;
    return true;
  }

  /** JS Number.isSafeInteger(id) && id >= 0. */
  private static boolean isSafeNonNegativeInteger(JsonNode id) {
    if (id == null || !id.isNumber()) return false;
    double value = id.asDouble();
    return value >= 0 && value == Math.rint(value) && value <= MAX_SAFE_INTEGER;
  }

  private static boolean isStringEqual(JsonNode node, String expected) {
    return node != null && node.isString() && node.asString().equals(expected);
  }

  private JsonNode read(
      CollectionInput input, String token, String path, Map<String, String> query, Scope scope) {
    return input
        .calls()
        .execute(
            "tiro.read",
            timeout -> client.get(token, path, query, timeout),
            error -> {
              if (scope == Scope.NOTE
                  && error instanceof TiroApiException api
                  && (api.status() == FORBIDDEN || api.status() == NOT_FOUND))
                throw new UnavailableNote();
              return classify(error);
            },
            input.cancel());
  }
}
