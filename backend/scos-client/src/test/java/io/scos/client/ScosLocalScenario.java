package io.scos.client;

import io.scos.client.collect.CollectLock;
import io.scos.client.collect.CollectionReport;
import io.scos.client.collect.IncrementalCollector;
import io.scos.client.collect.StateStore;
import io.scos.engine.ContextHit;
import io.scos.engine.SearchQuery;
import io.scos.spi.store.Principal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** 파일·state·색인 수명을 소유하며 운영 원천과 엔진 조립을 재사용한다. */
public final class ScosLocalScenario implements AutoCloseable {
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC);
  private final LocalInstallation installation;
  private final ClientPaths paths;
  private LocalEngine engine;
  private StateStore state;
  private IncrementalCollector collector;
  private ClientAssembly assembly;
  private final LocalCollectionFaults faults = new LocalCollectionFaults();

  public ScosLocalScenario(Path root) {
    installation = new LocalInstallation(root, CLOCK);
    paths = installation.paths();
  }

  public void givenSession() throws IOException {
    installation.givenSession();
  }

  public void givenMemory() throws IOException {
    installation.givenMemory();
  }

  public void givenProject() throws IOException {
    installation.givenProject();
  }

  public void givenSmallProjectWithRejectedLine() throws IOException {
    installation.givenSmallProjectWithRejectedLine();
  }

  public void cancelBeforeCollection() {
    collector().cancel();
  }

  public void appendUser(String text) throws IOException {
    installation.appendUser(text);
  }

  public void writeMemory(String text) throws IOException {
    installation.writeMemory(text);
  }

  public void deleteSession() throws IOException {
    installation.deleteSession();
  }

  public void deleteMemory() throws IOException {
    installation.deleteMemory();
  }

  public void givenMissingProjectsRoot() throws IOException {
    installation.givenMissingProjectsRoot();
  }

  private LocalEngine engine() {
    if (engine == null) engine = LocalEngine.open(paths, CLOCK);
    return engine;
  }

  private StateStore stateStore() {
    if (state == null) {
      PrivateFiles.directory(paths.home());
      state = StateStore.open(paths.state());
    }
    return state;
  }

  private IncrementalCollector collector() {
    if (collector == null)
      collector =
          new IncrementalCollector(
              stateStore(),
              faults.ingestor(engine().ingestor()),
              ClientPaths.SPACE,
              faults.sources(CollectionSession.sources(paths, CLOCK), this::cancel));
    return collector;
  }

  private void cancel() {
    collector.cancel();
  }

  public CollectionReport collect() {
    return collector().runOnce();
  }

  public void givenCollectedDocuments() {
    collect();
    if (!ids("대체는 별로").equals(List.of("transcript/d/s1"))
        || !ids("보라색 고양이").equals(List.of("memory/d/a.md"))) {
      throw new IllegalStateException("given 준비 실패: 세션과 메모리가 검색되어야 한다");
    }
  }

  public CollectionReport fullReproject() {
    return collector().fullReproject();
  }

  public List<ContextHit> search(String query) {
    return engine().engine().search(Principal.singleUser(), SearchQuery.of(query)).hits();
  }

  public List<String> ids(String query) {
    return search(query).stream().map(ContextHit::documentId).distinct().toList();
  }

  public Set<String> pendingTranscripts() {
    return stateStore().pendingProjection("claude-transcript");
  }

  public void failsProjection() {
    faults.failProjection();
    collector = null;
  }

  public void isolatesTranscripts() {
    faults.isolateTranscripts();
    collector = null;
  }

  public void failsAfterTranscriptCollection() {
    faults.failAfterTranscript();
    collector = null;
  }

  public void cancelsAfterFirstDocument() {
    faults.cancelAfterDocument();
    collector = null;
  }

  public void resumesNormally() {
    faults.reset();
    collector = null;
  }

  public void deleteIndex() throws IOException {
    restartAssembly();
    if (engine != null) engine.close();
    engine = null;
    collector = null;
    installation.deleteIndex();
  }

  public String storedStateBytes() throws IOException {
    return installation.stateBytes();
  }

  public String storedIndexBytes() throws IOException {
    return installation.indexBytes();
  }

  public void collectWithoutIndex() {
    try (var session = CollectionSession.stateOnly(paths, ClientOptions.defaults(CLOCK))) {
      var unused = session.collectOnce().orElseThrow();
    }
  }

  public Map<String, Set<String>> pending() {
    return Map.of(
        "claude-transcript",
        stateStore().pendingProjection("claude-transcript"),
        "claude-memory",
        stateStore().pendingProjection("claude-memory"));
  }

  public Path state() {
    return paths.state();
  }

  public Path index() {
    return paths.index();
  }

  public boolean lockAvailable() {
    var acquired = CollectLock.tryAcquire(paths.lock());
    acquired.ifPresent(CollectLock::close);
    return acquired.isPresent();
  }

  public Map<String, List<String>> recover() {
    collectWithLock();
    return Map.of("session", assemblyIds("대체는 별로"), "memory", assemblyIds("보라색 고양이"));
  }

  public void givenFailedAcquisition() {
    faults.failCreation();
    openAssembly();
    try {
      assembly.collectOnce();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new IllegalStateException("given 준비 실패: 수집기 생성이 실패하지 않았다");
  }

  private void openAssembly() {
    if (assembly == null)
      assembly =
          ClientAssembly.open(
              paths,
              new ClientOptions(
                  CLOCK, faults::create, () -> {}, delay -> false, CollectionCycles.CYCLE));
  }

  public Optional<CollectionReport> tryCollectWithLock() {
    openAssembly();
    return assembly.collectOnce();
  }

  CollectLock holdLock() {
    PrivateFiles.directory(paths.home());
    return CollectLock.tryAcquire(paths.lock()).orElseThrow();
  }

  void givenVersionOneInstallation() throws IOException, SQLException {
    installation.givenMemoryModifiedBeforeCollection();
    collectWithLock();
    if (!assemblyIds("보라색 고양이").equals(List.of("memory/d/a.md")))
      throw new IllegalStateException("given 준비 실패: 옛 설치의 메모리가 검색되어야 한다");
    restartAssembly();
    installation.givenVersionOneState();
  }

  String capturedAt(String query, String documentId) {
    openAssembly();
    return assembly.engine().search(Principal.singleUser(), SearchQuery.of(query)).hits().stream()
        .filter(hit -> hit.documentId().equals(documentId))
        .map(ContextHit::capturedAt)
        .findFirst()
        .orElseThrow();
  }

  String loggedSearch(String query) throws IOException {
    Path file = paths.home().resolve("query-log.jsonl");
    ClientPaths logged = new ClientPaths(paths.home(), paths.claudeProjects(), Optional.of(file));
    try (var loggedAssembly = ClientAssembly.open(logged, CLOCK)) {
      loggedAssembly.tools().searchContext(Principal.singleUser(), Map.of("query", query));
    }
    return JsonMapper.builder().build().readTree(Files.readString(file)).path("query").asString();
  }

  public CollectionReport collectWithLock() {
    openAssembly();
    return assembly.collectOnce().orElseThrow();
  }

  public List<String> assemblyIds(String query) {
    openAssembly();
    return assembly.engine().search(Principal.singleUser(), SearchQuery.of(query)).hits().stream()
        .map(ContextHit::documentId)
        .distinct()
        .toList();
  }

  private void restartAssembly() {
    if (assembly != null) assembly.close();
    assembly = null;
  }

  public void cancelCollection() {
    openAssembly();
    assembly.cancelCollection();
  }

  Map<String, String> environment() {
    return Map.of(
        "SCOS_HOME",
        paths.home().toString(),
        "SCOS_CLAUDE_PROJECTS",
        paths.claudeProjects().toString(),
        "SCOS_QUERY_LOG",
        "");
  }

  ScosStdioSession startCollectedStdio() {
    return ScosStdioSession.collected(environment());
  }

  ScosStdioSession startBlockedStdio() {
    return ScosStdioSession.blocked(environment());
  }

  ScosStdioSession startReadOnlyStdio() {
    return ScosStdioSession.readOnly(environment());
  }

  @Override
  public void close() {
    if (assembly != null) assembly.close();
    if (state != null) state.close();
    if (engine != null) engine.close();
  }
}
