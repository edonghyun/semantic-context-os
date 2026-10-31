package io.scos.client;

import io.scos.client.redact.Redaction;
import io.scos.engine.ContextEngine;
import io.scos.engine.ContextIngestor;
import io.scos.engine.ContextIngestorBuilder;
import io.scos.mcp.JsonlQueryLog;
import io.scos.mcp.McpTools;
import io.scos.mcp.QueryLog;
import io.scos.store.sqlite.SqliteIndex;
import java.time.Clock;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 로컬 색인과 그 위의 검색 엔진·도구를 소유한다. 수집 잠금이나 state는 열지 않는다. */
final class LocalEngine implements AutoCloseable {
  private final SqliteIndex index;
  private final ContextEngine engine;
  private final McpTools tools;
  private final Clock clock;

  private LocalEngine(SqliteIndex index, ContextEngine engine, McpTools tools, Clock clock) {
    this.index = index;
    this.engine = engine;
    this.tools = tools;
    this.clock = clock;
  }

  static LocalEngine open(ClientPaths paths, Clock clock) {
    PrivateFiles.directory(paths.home());
    PrivateFiles.database(paths.index());
    paths.queryLog().ifPresent(PrivateFiles::file);
    SqliteIndex index = SqliteIndex.open(paths.index());
    try {
      ContextEngine engine = engine(index, clock);
      QueryLog queryLog =
          paths
              .queryLog()
              .<QueryLog>map(
                  path -> new JsonlQueryLog(path, clock).mapQuery(Redaction.standard()::apply))
              .orElse(QueryLog.NONE);
      McpTools tools = new McpTools(engine, JsonMapper.builder().build(), queryLog);
      return new LocalEngine(index, engine, tools, clock);
    } catch (RuntimeException | Error failure) {
      index.close();
      throw failure;
    }
  }

  private static ContextEngine engine(SqliteIndex index, Clock clock) {
    return ContextEngine.builder()
        .units(index.units())
        .unrestricted(index.maintenance())
        .keywords(index.keywords())
        .vectors(index.vectors())
        .keywordOnly()
        .defaultSpace(ClientPaths.SPACE)
        .clock(clock)
        .build();
  }

  ContextEngine engine() {
    return engine;
  }

  McpTools tools() {
    return tools;
  }

  ContextIngestor ingestor() {
    return new ContextIngestorBuilder()
        .maintenance(index.maintenance())
        .keywords(index.keywords())
        .vectors(index.vectors())
        .meta(index.meta())
        .keywordOnly()
        .cache(index.embeddingCache())
        .transactions(index.transactions())
        .clock(clock)
        .build();
  }

  boolean isEmpty(List<String> kinds) {
    return kinds.stream()
        .allMatch(kind -> index.maintenance().listDocumentIds(ClientPaths.SPACE, kind).isEmpty());
  }

  @Override
  public void close() {
    index.close();
  }
}
