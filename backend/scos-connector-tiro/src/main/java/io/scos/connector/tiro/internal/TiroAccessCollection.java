package io.scos.connector.tiro.internal;

import io.scos.connector.tiro.internal.TiroReads.Scope;
import io.scos.engine.EventLog;
import io.scos.spi.access.AccessFailureReason;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.AclSink;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.RawAcl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tiro 접근 단계 수집기(Tiro 접근 설계 §5): 폴더 목록을 한 번 받아 유효 공개 폴더를 가리고({@link TiroFolders}), 폴더별 노트 목록으로 전원
 * 공개 노트 집합 = (공개 폴더 노트) − (비공개 폴더 노트)를 만든 뒤, 대상 노트마다 저장 원본의 협업자로 키·신원을 낸다({@link TiroNoteAccess}).
 * 폴더 쪽 실패(호출·형식·취소)는 대상 처리 전에 그대로 전파한다 — 그 run은 ACL을 하나도 내지 않는다. 대상 하나의 원본 문제는 그 대상만 FAILED로 닫고
 * 계속한다.
 */
public final class TiroAccessCollection {
  static final int FOLDER_LIST_PAGE_SIZE = 100;
  private static final Logger log = LoggerFactory.getLogger(TiroAccessCollection.class);
  private static final String NOTE = "note:";
  private static final Pattern TARGET = Pattern.compile("^note:[A-Za-z0-9_-]+$");

  private final CredentialResolver credentials;
  private final TiroApiClient client;
  private final ObjectMapper json;
  private final Clock clock;

  public TiroAccessCollection(
      CredentialResolver credentials, TiroApiClient client, ObjectMapper json, Clock clock) {
    this.credentials = credentials;
    this.client = client;
    this.json = json;
    this.clock = clock;
  }

  /** 대상이 비어도 폴더를 읽는다(폴더 실패를 숨기지 않게). 비용은 실패해도 finally에서 한 번 보고한다. */
  public void collect(AccessInput input, AclSink sink) {
    TiroConfiguration config = TiroConfiguration.read(json, input.source().config());
    String token =
        CredentialResolver.headerValue(credentials.resolve(input.source().credentialRef()));
    TiroReads reads = new TiroReads(input.calls(), input.cancel(), client, token);
    TiroNoteAccess notes = new TiroNoteAccess(input.source().id(), input.domains(), json);
    Instant started = clock.instant();
    int folderCount = 0;
    int publicNotes = 0;
    try {
      String workspace = "/v1/external/workspaces/" + config.workspaceGuid();
      TiroFolders folders =
          TiroFolders.read(reads.read(workspace + "/folders", Map.of(), Scope.WORKSPACE));
      folderCount = folders.publicIds().size() + folders.privateIds().size();
      Set<String> everyone = notesIn(reads, workspace + "/notes", folders.publicIds());
      everyone.removeAll(notesIn(reads, workspace + "/notes", folders.privateIds()));
      publicNotes = everyone.size();
      for (String id : input.externalIds()) sink.accept(observe(notes, input, id, everyone));
    } finally {
      long elapsedMs = Math.max(0, Duration.between(started, clock.instant()).toMillis());
      sink.cost(
          new AccessCost(input.externalIds().size(), OptionalLong.of(reads.attempts()), elapsedMs));
      EventLog.info(log, "collection.tiro_access.finished")
          .source(input.source().id())
          .detail(
              "targetCount",
              input.externalIds().size(),
              "folderCount",
              folderCount,
              "publicNotes",
              publicNotes,
              "calls",
              reads.attempts(),
              "elapsedMs",
              elapsedMs)
          .log();
    }
  }

  /** 이 폴더들(id 순)의 노트 목록(하위 폴더 재귀)을 끝까지 걸어 노트 guid 합집합을 낸다. 쿼리 folderId는 폴더 id다. */
  private static Set<String> notesIn(TiroReads reads, String listPath, Set<String> folderIds) {
    Set<String> guids = new HashSet<>();
    for (String folderId : new TreeSet<>(folderIds)) {
      CursorWalk walk =
          new CursorWalk(listPath, FOLDER_LIST_PAGE_SIZE, "Tiro folder pagination did not advance");
      Optional<String> cursor = Optional.empty();
      do {
        Map<String, String> query = walk.query(cursor);
        query.put("folderId", folderId);
        TiroCursorPage page = TiroCursorPage.read(reads.read(listPath, query, Scope.WORKSPACE));
        // 받은 커서가 이미 요청한 커서면 다음 query()가 위반으로 멈춘다 — 이 쪽의 guid를 먼저 모아도 결과(예외)는 같다
        page.entries().forEach(listed -> guids.add(TiroCollection.guid(listed)));
        cursor = page.nextCursor();
      } while (cursor.isPresent());
    }
    return guids;
  }

  /** 대상 하나: 형식이 틀리면 INVALID_CARRIER, 원본이 없으면 DOCUMENT_MISSING, 원본을 읽을 수 없으면 ACL_UNREADABLE. */
  private AclPage observe(
      TiroNoteAccess notes, AccessInput input, String id, Set<String> everyone) {
    Instant at = clock.instant();
    if (!TARGET.matcher(id).matches()) return failed(id, AccessFailureReason.INVALID_CARRIER, at);
    Optional<String> stored = input.storedContent().apply(id);
    if (stored.isEmpty()) return failed(id, AccessFailureReason.DOCUMENT_MISSING, at);
    try {
      TiroNoteAccess.Observation observation =
          notes.observe(stored.get(), everyone.contains(id.substring(NOTE.length())), at);
      return new AclPage(Map.of(id, observation.acl()), List.of(), observation.identities());
    } catch (TiroProtocolException unreadable) {
      return failed(id, AccessFailureReason.ACL_UNREADABLE, at);
    }
  }

  private static AclPage failed(String id, AccessFailureReason reason, Instant at) {
    return new AclPage(Map.of(id, RawAcl.failed(reason, at)), List.of(), List.of());
  }
}
