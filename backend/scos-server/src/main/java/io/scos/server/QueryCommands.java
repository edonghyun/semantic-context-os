package io.scos.server;

import io.scos.collection.internal.RawCatalogCursor;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.RawCatalogQuery;
import io.scos.collection.internal.SourceQueries.RawState;
import io.scos.engine.EmbeddingGenerationCheck;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.SpaceId;
import io.scos.spi.store.SpaceMeta;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 운영 조회 명령(source list·status·rejections, raw list·get과 status.sh 대체 `status`). 출력 JSON(들여쓰기 2칸;
 * rejections만 항목마다 한 줄)은 기존 클라이언트가 읽는 형식이라 바꾸지 않는다. 옵션은 Spring 형식(`--limit=5`)이고 DB는
 * SCOS_DB_URL이다(TS `--db` 없음, ADR-0024 §1). 쓰기는 하지 않는다.
 */
final class QueryCommands {
  static final int REJECTIONS_DEFAULT_LIMIT = 20;
  static final int REJECTIONS_MAX_LIMIT = 200;
  static final int RAW_DEFAULT_LIMIT = 100;

  /** status: 최근 run 6건, serve.jsonl의 warn·error 마지막 3줄. */
  static final int OVERVIEW_RUNS = 6;

  static final int OVERVIEW_LOG_LINES = 3;

  private static final Pattern LOG_PROBLEM = Pattern.compile("\"level\":\"(warn|error)\"");

  private final SourceQueries queries;
  private final SourceAdapters adapters;
  private final FreshnessReader freshness;
  private final Set<SpaceId> spaces;
  private final SpaceMeta meta;
  private final EmbeddingGeneration configured;
  private final Clock clock;
  private final SourceQueryJson render;
  private final CommandOutput out;

  QueryCommands(
      SourceQueries queries,
      SourceAdapters adapters,
      FreshnessReader freshness,
      Set<SpaceId> spaces,
      SpaceMeta meta,
      EmbeddingGeneration configured,
      Clock clock,
      SourceQueryJson render,
      CommandOutput out) {
    this.queries = queries;
    this.adapters = adapters;
    this.freshness = freshness;
    this.spaces = Set.copyOf(spaces);
    this.meta = meta;
    this.configured = configured;
    this.clock = clock;
    this.render = render;
    this.out = out;
  }

  /** `source list|status <id>|rejections <id> [--limit=<n>]`. */
  void source(String sub, CommandArguments args) {
    switch (sub) {
      case "list" -> out.write(JsJson.pretty(render.sources(queries.listSources(), adapters)));
      case "status" ->
          out.write(
              JsJson.pretty(render.status(queries.status(sourceArgument(args), clock.instant()))));
      case "rejections" -> {
        int limit =
            Math.min(
                args.positiveInteger("limit").orElse(REJECTIONS_DEFAULT_LIMIT),
                REJECTIONS_MAX_LIMIT);
        for (var entry : queries.recentRejections(sourceArgument(args), limit))
          out.write(JsJson.stringify(render.rejection(entry)));
      }
      default -> throw new IllegalArgumentException(Commands.USAGE);
    }
  }

  /**
   * `raw list [--limit=] [--cursor=] [--source=] [--type=] [--state=active|deleted]`, `raw get
   * <source> <externalId>`.
   */
  void raw(String sub, CommandArguments args) {
    switch (sub) {
      case "list" -> {
        RawCatalogQuery query =
            new RawCatalogQuery(
                args.positiveInteger("limit").orElse(RAW_DEFAULT_LIMIT),
                args.option("cursor").map(RawCatalogCursor::decode),
                args.option("source").map(SourceInstanceId::new),
                args.option("type"),
                args.choice("state", RawState.values(), RawState::wire));
        out.write(JsJson.pretty(render.page(queries.rawList(query))));
      }
      case "get" ->
          out.write(
              JsJson.pretty(
                  render.record(queries.rawGet(sourceArgument(args), args.positional(3)))));
      default -> throw new IllegalArgumentException(Commands.USAGE);
    }
  }

  /**
   * `status [--log=<serve.jsonl>]`: 소스별 상태·최근 run·점유·공간별 엔진 최신성과 임베딩 세대(저장값·설정값)·로그 경고(launchd는 보지
   * 않는다).
   */
  void overview(CommandArguments args) {
    Instant now = clock.instant();
    List<SourceQueries.SourceStatus> statuses =
        queries.listSources().stream()
            .map(SourceInstance::id)
            .flatMap(id -> queries.status(id, now).stream())
            .toList();
    List<SourceQueryJson.SpaceOverview> spaceOverviews =
        spaces.stream()
            .sorted(Comparator.comparing(SpaceId::value))
            .map(
                space ->
                    new SourceQueryJson.SpaceOverview(
                        space,
                        freshness.read(AccessScope.singleUser(), space),
                        meta.get(space, EmbeddingGenerationCheck.META_KEY),
                        configured))
            .toList();
    out.write(
        JsJson.pretty(
            render.overview(
                now,
                statuses,
                queries.recentRuns(OVERVIEW_RUNS),
                queries.claims(),
                spaceOverviews,
                args.option("log").map(QueryCommands::logProblems))));
  }

  /** 기계 갈래 로그(serve.jsonl)의 `"level":"warn|error"` 줄 중 마지막 몇 줄. 없거나 못 읽으면 found=false. */
  static SourceQueryJson.LogProblems logProblems(String path) {
    Path file = Path.of(path);
    if (!Files.isReadable(file)) return new SourceQueryJson.LogProblems(path, false, List.of());
    Deque<String> last = new ArrayDeque<>();
    // 깨진 UTF-8 바이트는 대체 문자로 읽는다(InputStreamReader 기본) — 로그 한 줄 때문에 명령이 실패하지 않게
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!LOG_PROBLEM.matcher(line).find()) continue;
        if (last.size() == OVERVIEW_LOG_LINES) last.removeFirst();
        last.addLast(line);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new SourceQueryJson.LogProblems(path, true, List.copyOf(last));
  }

  private static SourceInstanceId sourceArgument(CommandArguments args) {
    return new SourceInstanceId(args.positional(2));
  }
}
