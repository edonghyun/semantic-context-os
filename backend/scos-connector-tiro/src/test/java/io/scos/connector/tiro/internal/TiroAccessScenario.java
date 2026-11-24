package io.scos.connector.tiro.internal;

import io.scos.connector.tiro.TiroAdapter;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.AccessCost;
import io.scos.spi.connector.AccessInput;
import io.scos.spi.connector.AccessPolicy;
import io.scos.spi.connector.AclPage;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.RawAcl;
import io.scos.spi.connector.RecordedAclSink;
import io.scos.spi.source.SourceIdentityRecord;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.TestMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;

/**
 * Tiro 접근 수집 시험의 연결: 가짜 Tiro(폴더 목록 기본값은 빈 배열), 기다리지 않는 공급자 실행기, 시계, 저장 원본 표, 실제 어댑터의 접근 정책. 본문은 "어떤
 * 폴더·폴더별 노트·저장 원본으로 어떤 대상을 수집하면 무엇이 나오는가"만 쓴다.
 */
final class TiroAccessScenario implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");
  static final SourceInstanceId SOURCE = TiroAssembly.SOURCE;
  static final String FOLDERS = "/v1/external/workspaces/ws-synthetic/folders";
  static final String LIST = "/v1/external/workspaces/ws-synthetic/notes";
  static final String CONFIG = "{\"workspaceGuid\":\"ws-synthetic\",\"allNotes\":true}";
  private static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.com"), Map.of(SOURCE, "example.com"));

  private final FakeTiroServer server = TiroAssembly.server();
  private final MutableClock clock = new MutableClock(NOW);
  private final CancellationToken cancel = new CancellationToken();
  private final ProviderCallExecutor calls =
      new ProviderCallExecutor(
          ProviderCallPolicy.defaults(() -> 0.0),
          clock,
          (delay, token) -> token.isCancelled(),
          TestMetrics.providerCalls());
  private final Map<String, String> stored = new HashMap<>();

  TiroAccessScenario() {
    server.folders("[]");
  }

  /** 폴더 하나의 목록 항목(JSON). parentId가 null이면 최상위. */
  static String folder(String id, String parentId, String sharingType) {
    return "{\"id\":\""
        + id
        + "\",\"parentId\":"
        + (parentId == null ? "null" : "\"" + parentId + "\"")
        + ",\"sharingType\":\""
        + sharingType
        + "\"}";
  }

  // ---- 준비 ----

  void givenFolders(String... folders) {
    server.folders("[" + String.join(",", folders) + "]");
  }

  void givenFolderListing(String body) {
    server.folders(body);
  }

  /** 이 폴더의 재귀 노트 목록. */
  void givenFolderNotes(String folderId, String... guids) {
    server.folderNotes(folderId, List.of(guids));
  }

  void givenFolderNotes(String folderId, List<String> guids) {
    server.folderNotes(folderId, guids);
  }

  /** 노트 원본(`note:<guid>`)을 저장해 둔다: 협업자는 이 이메일들(OWNER). */
  void givenStored(String guid, String... collaboratorEmails) {
    List<String> collaborators = new ArrayList<>();
    for (String email : collaboratorEmails)
      collaborators.add("{\"email\":\"" + email + "\",\"role\":\"OWNER\"}");
    givenStoredContent(
        guid,
        "{\"note\":{\"guid\":\""
            + guid
            + "\",\"collaborators\":["
            + String.join(",", collaborators)
            + "]},\"paragraphs\":[],\"summaries\":[],\"documents\":[],\"unavailableResources\":[]}");
  }

  void givenStoredContent(String guid, String content) {
    stored.put("note:" + guid, content);
  }

  void failNext(String path, int times, FakeTiroServer.Fault fault) {
    server.failNext(path, times, fault);
  }

  void failOnCursor(String path, String cursor, int times, FakeTiroServer.Fault fault) {
    server.failOnCursor(path, cursor, times, fault);
  }

  /** 경로+쿼리에 이 문자열이 든 첫 요청이 도착하면 취소한다(그 요청은 답한다). */
  void cancelOnRequest(String pathContains) {
    server.gateOn(pathContains, new CountDownLatch(0), arrived -> cancel.cancel());
  }

  // ---- 수집 ----

  Result collect(String... targets) {
    Result result = new Result();
    RecordedAclSink sink = new RecordedAclSink();
    AccessInput input =
        new AccessInput(
            TiroAssembly.instance(CONFIG, NOW).descriptor(),
            List.of(targets),
            calls,
            cancel,
            DOMAINS,
            id -> Optional.ofNullable(stored.get(id)));
    try {
      accessPolicy().collect(input, sink);
    } catch (RuntimeException failure) {
      result.failure = Optional.of(failure);
    }
    result.pages.addAll(sink.pages());
    result.costs.addAll(sink.costs());
    return result;
  }

  AccessPolicy accessPolicy() {
    return adapter().accessPolicy();
  }

  List<String> requests() {
    return server.requests();
  }

  private TiroAdapter adapter() {
    return TiroAssembly.adapter(server.baseUrl(), server.token(), clock);
  }

  @Override
  public void close() {
    try {
      server.assertNoUnmatched();
    } finally {
      server.close();
    }
  }

  /** 한 번의 접근 수집: 낸 페이지·비용, 또는 던진 예외. */
  static final class Result {
    private final List<AclPage> pages = new ArrayList<>();
    private final List<AccessCost> costs = new ArrayList<>();
    private Optional<RuntimeException> failure = Optional.empty();

    Map<String, RawAcl> acls() {
      Map<String, RawAcl> all = new LinkedHashMap<>();
      pages.forEach(page -> all.putAll(page.acls()));
      return all;
    }

    RawAcl.Observed acl(String externalId) {
      RawAcl acl = acls().get(externalId);
      if (!(acl instanceof RawAcl.Observed observed))
        throw new IllegalStateException("관측 없음: " + externalId);
      return observed;
    }

    /** 이 대상 ACL의 키(문자열, 정렬). */
    Set<String> keys(String externalId) {
      Set<String> out = new TreeSet<>();
      acl(externalId).keys().forEach(k -> out.add(k.toString()));
      return out;
    }

    List<SourceIdentityRecord> identities() {
      return pages.stream().flatMap(page -> page.identities().stream()).toList();
    }

    List<AccessCost> costs() {
      return List.copyOf(costs);
    }

    Optional<RuntimeException> failure() {
      return failure;
    }
  }
}
