package io.scos.connector.googledrive.internal;

import io.scos.connector.googledrive.internal.DriveCalls.Availability;
import io.scos.connector.googledrive.internal.UnavailableContent.SpreadsheetVersionChanged;
import io.scos.engine.support.call.CursorTrail;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.PageSink;
import io.scos.spi.connector.RawContentTooLargeException;
import io.scos.spi.connector.RawMutation;
import io.scos.spi.connector.RejectedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drive run 하나가 소유하는 걷기.OAuth 계정이 설정 계정인지 먼저 본 뒤, 작업 큐(`pending`)의 앞 작업 하나마다 페이지 하나를 낸다 — 런타임이 페이지를
 * 커밋하므로 체크포인트는 그 페이지까지 확정된 큐다({@link DriveProgress}).
 *
 * <ul>
 *   <li>변경 토큰은 탐색 전에 확보한다(사용자는 run 시작에, 공유 드라이브는 목록에서 처음 볼 때) — 탐색 중의 변경을 다음 피드가 다시 준다.
 *   <li>변경 피드: 휴지통·접근 상실(파일 없는 removed)은 삭제, 접근 가능한 corpus 이동·그 밖의 변경은 파일 다시 수집, 드라이브 removed는 그
 *       드라이브의 알려진 파일과 드라이브를 삭제한다. 목록에 없다는 이유로는 지우지 않는다(coverage는 늘 incremental).
 *   <li>삭제는 파일·원본과 체크포인트에 기록한 모든 시트 범위 원본을 지우고, 큐에 남은 그 파일의 범위 작업을 버린다.
 *   <li>시트 범위는 읽기 전후로 파일 버전을 본다. 바뀌었으면 그 범위를 거부하고 같은 버전의 남은 범위를 버린다 — 다음 탐색이 새 버전을 읽는다.
 *   <li>읽을 수 없는 본문·파일·범위({@link UnavailableContent})는 거부로 남기고 계속한다. 그 밖의 실패는 run 실패다.
 * </ul>
 */
public final class GoogleDriveCollection {
  static final String SHEET_RANGE_FIELDS =
      "spreadsheetId,sheets(properties,data(startRow,startColumn,rowData(values(formattedValue,userEnteredValue,effectiveValue,hyperlink,note))))";
  static final String CHANGE_FIELDS =
      "nextPageToken,newStartPageToken,changes(changeType,fileId,driveId,removed,file(id,trashed),drive(id))";
  static final String PAGE_SIZE = "100";
  private static final String PAGE_TOKEN_STUCK = "Google Drive page token did not advance";

  /**
   * 목록 걷기(변경 피드 하나, 파일 목록 하나, 드라이브 목록)마다 이미 요청한 토큰: (걷기, 다음 토큰) → 그 걷기의 자취. 이어지는 페이지는 작업 대기열을 거쳐
   * 오므로 다음 토큰으로 찾아 잇는다. 체크포인트에서 재개한 걷기는 그 토큰부터 새 자취다.
   */
  private final Map<Walk, CursorTrail> trails = new ConcurrentHashMap<>();

  private record Walk(String list, String token) {}

  private final CollectionInput input;
  private final DriveCalls calls;
  private final DriveFileCollector files;
  private final ObjectMapper json;
  private final Clock clock;

  public GoogleDriveCollection(
      CollectionInput input,
      GoogleDriveConfiguration config,
      GoogleDriveSession session,
      ObjectMapper json,
      Clock clock) {
    this.input = input;
    this.calls = new DriveCalls(input, session, config, json);
    this.files = new DriveFileCollector(calls);
    this.json = json;
    this.clock = clock;
  }

  public void collect(PageSink sink) {
    JsonNode user =
        JsValues.object(
            calls
                .json(
                    GoogleService.DRIVE,
                    "/drive/v3/about?fields=user(emailAddress,permissionId)",
                    Availability.REQUIRED)
                .get("user"));
    JsonNode email = user.get("emailAddress");
    JsonNode permissionId = user.get("permissionId");
    if (email == null
        || !email.isString()
        || !calls.isConfiguredAccount(email.asString())
        || permissionId == null
        || !permissionId.isString())
      throw new GoogleProtocolException(
          "Google OAuth account does not match the configured Drive account");
    DriveProgress progress =
        DriveProgress.resume(json, input.checkpoint(), permissionId.asString());
    if (progress.changeTokens().saved(Optional.empty()).isEmpty())
      progress.changeTokens().record(Optional.empty(), startToken(Optional.empty()));
    while (progress.hasPending()) {
      if (input.cancel().isCancelled())
        throw new CallFailureException(
            "google-drive.collect", CallFailure.of(CallFailure.Kind.CANCELLED, "cancelled"));
      DriveTask task = progress.head();
      Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
      Step step = new Step();
      try {
        run(task, progress, observedAt, step);
      } catch (UnavailableContent e) {
        // 이 버전의 남은 범위는 모두 무효다 — 다음 탐색이 새 버전을 읽는다
        if (e instanceof SpreadsheetVersionChanged && task instanceof DriveTask.Sheet changed)
          progress.dropQueued(
              queued ->
                  queued instanceof DriveTask.Sheet sheet
                      && sheet.id().equals(changed.id())
                      && sheet.version().equals(changed.version()));
        step.rejections.add(DriveCalls.rejection(task.json(), e.reason(), observedAt));
      }
      progress.advance(step.next);
      sink.accept(new CollectionPage(step.mutations, step.rejections, progress.checkpoint()));
    }
  }

  /** 작업 하나가 만든 것: 원본 변경, 거부, 큐 앞에 넣을 다음 작업. */
  private static final class Step {
    final List<RawMutation> mutations = new ArrayList<>();
    final List<RejectedItem> rejections = new ArrayList<>();
    final List<DriveTask> next = new ArrayList<>();
  }

  private void run(DriveTask task, DriveProgress progress, Instant observedAt, Step step) {
    switch (task) {
      case DriveTask.Changes changes -> changes(changes, progress, step);
      case DriveTask.Remove remove -> remove(remove, progress, observedAt, step);
      case DriveTask.File file -> file(file, progress, observedAt, step);
      case DriveTask.Sheet sheet -> sheet(sheet, observedAt, step);
      case DriveTask.Files files -> files(files, progress, step);
      case DriveTask.Drives drives -> drives(drives, progress, observedAt, step);
    }
  }

  // ---- 변경 피드 ----

  private void changes(DriveTask.Changes task, DriveProgress progress, Step step) {
    Optional<String> token =
        task.pageToken().or(() -> progress.changeTokens().saved(task.driveId()));
    if (token.isEmpty()) throw new GoogleProtocolException("Missing Google Drive change token");
    String current = token.get();
    ObjectNode response =
        calls.json(
            GoogleService.DRIVE,
            "/drive/v3/changes?" + GoogleQuery.form(changesQuery(current, task.driveId())),
            Availability.REQUIRED);
    DriveChangePage page = DriveChangePage.read(response);
    if (page.continues())
      advance(
          "changes:" + task.driveId().orElse(""),
          Optional.of(current),
          page.continuation(),
          "Google Drive change token did not advance");
    step.next.addAll(page.tasks());
    progress.changeTokens().record(task.driveId(), page.continuation());
    if (page.continues()) step.next.add(task.withPageToken(page.continuation()));
  }

  private static Map<String, String> changesQuery(String pageToken, Optional<String> driveId) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageToken", pageToken);
    query.put("pageSize", PAGE_SIZE);
    query.put("spaces", "drive");
    query.put("supportsAllDrives", "true");
    query.put("includeItemsFromAllDrives", "true");
    query.put("includeRemoved", "true");
    query.put("includeCorpusRemovals", "true");
    query.put("fields", CHANGE_FIELDS);
    driveId.ifPresent(d -> query.put("driveId", d));
    return query;
  }

  // ---- 삭제 ----

  private void remove(
      DriveTask.Remove task, DriveProgress progress, Instant observedAt, Step step) {
    String target = task.id();
    boolean drive = task.drive();
    List<String> ids = drive ? progress.filesInDrive(target) : List.of(target);
    for (String id : ids) {
      List<String> externalIds =
          new ArrayList<>(List.of(RawKind.FILE.externalId(id), RawKind.BINARY.externalId(id)));
      externalIds.addAll(progress.children(id));
      for (String externalId : externalIds)
        step.mutations.add(DriveCalls.remove(externalId, observedAt));
      progress.forget(id);
    }
    if (drive) {
      step.mutations.add(DriveCalls.remove(RawKind.DRIVE.externalId(target), observedAt));
      progress.changeTokens().forgetDrive(target);
    }
    Set<String> removed = Set.copyOf(ids);
    // 지운 파일의 범위 작업, 드라이브 회수면 그 드라이브를 가리키는("driveId" 키가 있는) 작업
    progress.dropQueued(
        queued ->
            (queued instanceof DriveTask.Sheet sheet && removed.contains(sheet.id()))
                || (drive && JsValues.is(queued.json().get("driveId"), target)));
  }

  // ---- 파일 ----

  private void file(DriveTask.File task, DriveProgress progress, Instant observedAt, Step step) {
    String id = task.id();
    DriveFileCollector.Collected file =
        files.collect(id, observedAt, progress.downloadedVersions());
    step.mutations.addAll(file.mutations());
    step.rejections.addAll(file.rejections());
    step.next.addAll(file.next());
    file.metadata()
        .ifPresent(
            metadata -> {
              // 옛 범위 원본 id도 남긴다 — 삭제가 수집했던 모든 버전을 지워야 한다
              Set<String> children = new LinkedHashSet<>(progress.children(id));
              for (DriveTask.Sheet child : file.next())
                children.add(RawKind.SHEET.externalId(child.id() + ":" + child.key()));
              JsonNode driveId = metadata.get("driveId");
              progress.recordFile(
                  id,
                  driveId != null && driveId.isString()
                      ? Optional.of(driveId.asString())
                      : Optional.empty(),
                  List.copyOf(children));
            });
    if (file.mutations().stream().anyMatch(m -> m instanceof RawMutation.Delete)) {
      for (String child : progress.children(id))
        step.mutations.add(DriveCalls.remove(child, observedAt));
      progress.forget(id);
    }
  }

  // ---- 시트 범위 ----

  private void sheet(DriveTask.Sheet task, Instant observedAt, Step step) {
    String id = task.id();
    String version = task.version();
    String versionPath =
        "/drive/v3/files/"
            + GoogleQuery.component(id)
            + "?supportsAllDrives=true&fields=id,version";
    try {
      ObjectNode before = calls.json(GoogleService.DRIVE, versionPath, Availability.OPTIONAL);
      if (!JsValues.string(before.get("version")).equals(version))
        throw new SpreadsheetVersionChanged(
            "Spreadsheet changed during ranged collection; range omitted until next scan");
      Map<String, String> query = new LinkedHashMap<>();
      query.put("ranges", task.range());
      query.put("fields", SHEET_RANGE_FIELDS);
      ObjectNode workspace =
          calls.json(
              GoogleService.SHEETS,
              "/v4/spreadsheets/" + GoogleQuery.component(id) + "?" + GoogleQuery.form(query),
              Availability.OPTIONAL);
      ObjectNode after = calls.json(GoogleService.DRIVE, versionPath, Availability.OPTIONAL);
      if (!JsValues.string(after.get("version")).equals(version))
        throw new SpreadsheetVersionChanged(
            "Spreadsheet changed while reading a range; range omitted until next scan");
      ObjectNode value = json.createObjectNode();
      value.set("workspace", workspace);
      value.put("version", version);
      value.put("fileId", id);
      step.mutations.add(
          calls.upsert(RawKind.SHEET, id + ":" + task.key(), value, observedAt, task.file()));
    } catch (RawContentTooLargeException e) {
      // 고정 문구 메시지 — requireNonNull은 실패하지 않는다
      throw new UnavailableContent(Objects.requireNonNull(e.getMessage()));
    }
  }

  // ---- 목록 ----

  /** 파일 목록 한 페이지: 이번 탐색에서 처음 본 파일마다 파일 작업. */
  private void files(DriveTask.Files task, DriveProgress progress, Step step) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageSize", PAGE_SIZE);
    task.pageToken().ifPresent(t -> query.put("pageToken", t));
    query.put("q", "trashed = false");
    query.put("spaces", "drive");
    query.put("corpora", task.driveId().isPresent() ? "drive" : "user");
    query.put("includeItemsFromAllDrives", "true");
    query.put("supportsAllDrives", "true");
    query.put("fields", "nextPageToken,incompleteSearch,files(id)");
    task.driveId().ifPresent(d -> query.put("driveId", d));
    ObjectNode page = listingPage("files", query);
    for (JsonNode entry : entries(page, "files")) {
      String id = identity(entry);
      if (progress.firstSeen(id)) step.next.add(DriveTask.file(id));
    }
    nextPageToken(page)
        .ifPresent(
            t -> {
              advance("files:" + task.driveId().orElse(""), task.pageToken(), t, PAGE_TOKEN_STUCK);
              step.next.add(task.withPageToken(t));
            });
  }

  /** 공유 드라이브 목록 한 페이지: 드라이브마다 변경 토큰(처음 볼 때)·피드·파일 목록 작업과 드라이브 원본. */
  private void drives(
      DriveTask.Drives task, DriveProgress progress, Instant observedAt, Step step) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageSize", PAGE_SIZE);
    task.pageToken().ifPresent(t -> query.put("pageToken", t));
    query.put("fields", "nextPageToken,drives(id,name)");
    ObjectNode page = listingPage("drives", query);
    for (JsonNode entry : entries(page, "drives")) {
      String id = identity(entry);
      ObjectNode item = (ObjectNode) entry;
      if (progress.changeTokens().saved(Optional.of(id)).isEmpty())
        progress.changeTokens().record(Optional.of(id), startToken(Optional.of(id)));
      step.next.add(DriveTask.changesOf(id));
      step.next.add(DriveTask.filesOf(id));
      step.mutations.add(calls.upsert(RawKind.DRIVE, id, item, observedAt, item));
    }
    nextPageToken(page)
        .ifPresent(
            t -> {
              advance("drives", task.pageToken(), t, PAGE_TOKEN_STUCK);
              step.next.add(task.withPageToken(t));
            });
  }

  /**
   * 이 걷기에서 requested로 요청한 페이지의 다음 토큰 next로 자취를 잇는다. next가 이 걷기에서 이미 요청한 토큰이면(A → A·A → B → A)
   * violation에 걷기 설명(목록·토큰 지문)을 붙여 실패한다.
   */
  private void advance(String list, Optional<String> requested, String next, String violation) {
    CursorTrail trail =
        requested.map(t -> trails.remove(new Walk(list, t))).orElseGet(CursorTrail::new);
    trail.request(requested);
    if (trail.revisits(Optional.of(next)))
      throw new GoogleProtocolException(
          violation + " (" + trail.cycle(list, Optional.of(next)) + ")");
    trails.put(new Walk(list, next), trail);
  }

  private ObjectNode listingPage(String kind, Map<String, String> query) {
    ObjectNode page =
        calls.json(
            GoogleService.DRIVE,
            "/drive/v3/" + kind + "?" + GoogleQuery.form(query),
            Availability.REQUIRED);
    if (JsValues.isTrue(page.get("incompleteSearch")))
      throw new GoogleProtocolException(
          "Google Drive search is incomplete; refusing to mark this page complete");
    return page;
  }

  /** 목록 봉투의 항목 배열. */
  private static JsonNode entries(ObjectNode page, String key) {
    JsonNode entries = page.get(key);
    if (entries == null || !entries.isArray())
      throw new GoogleProtocolException("Invalid Google Drive listing");
    return entries;
  }

  /** 항목 하나의 id(항목마다 차례로 검사한다 — 앞 항목의 처리는 이미 일어났다). */
  private static String identity(JsonNode entry) {
    JsonNode id = JsValues.object(entry).get("id");
    if (!JsValues.nonEmptyString(id))
      throw new GoogleProtocolException("Google Drive listing has no identity");
    return id.asString();
  }

  /** 다음 페이지 토큰(있으면): 비었으면 실패한다. 이미 요청한 토큰으로 돌아오는지(A → A·A → B → A)는 {@link #advance}가 본다. */
  private static Optional<String> nextPageToken(ObjectNode page) {
    JsonNode next = page.get("nextPageToken");
    if (next == null) return Optional.empty();
    if (!JsValues.nonEmptyString(next)) throw new GoogleProtocolException(PAGE_TOKEN_STUCK);
    return Optional.of(next.asString());
  }

  private String startToken(Optional<String> driveId) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("supportsAllDrives", "true");
    driveId.ifPresent(d -> query.put("driveId", d));
    JsonNode token =
        calls
            .json(
                GoogleService.DRIVE,
                "/drive/v3/changes/startPageToken?" + GoogleQuery.form(query),
                Availability.REQUIRED)
            .get("startPageToken");
    if (!JsValues.nonEmptyString(token))
      throw new GoogleProtocolException("Invalid Google Drive start token");
    return token.asString();
  }
}
