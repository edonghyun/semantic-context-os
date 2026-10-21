package io.scos.server;

import io.scos.collection.internal.RawCatalogCursor;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceQueries;
import io.scos.collection.internal.SourceQueries.RawCatalogQuery;
import io.scos.collection.internal.SourceQueries.RawState;
import io.scos.engine.internal.search.FreshnessReader;
import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.SpaceId;
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
import org.springframework.boot.ApplicationArguments;

/**
 * 운영 조회 명령(TS source-sync-cli의 source list·status·rejections, raw list·get과 status.sh 대체 `status`).
 * 출력은 TS와 같은 JSON(들여쓰기 2칸; rejections만 항목마다 한 줄)이다. 옵션은 Spring 형식(`--limit=5`)이고 DB는
 * SCOS_DB_URL이다(TS `--db` 없음, ADR-0024 §1). 쓰기는 하지 않는다.
 */
final class QueryCommands {
  static final int REJECTIONS_DEFAULT_LIMIT = 20;
  static final int REJECTIONS_MAX_LIMIT = 200;
  static final int RAW_DEFAULT_LIMIT = 100;

  /** status.sh: 최근 run 6건, serve 로그의 warn·error 마지막 3줄. */
  static final int OVERVIEW_RUNS = 6;

  static final int OVERVIEW_LOG_LINES = 3;

  private static final Pattern LOG_PROBLEM = Pattern.compile("\"level\":\"(warn|error)\"");

  private final SourceQueries queries;
  private final SourceAdapters adapters;
  private final FreshnessReader freshness;
  private final Set<SpaceId> spaces;
  private final Clock clock;
  private final SourceQueryJson render;
  private final CommandOutput out;

  QueryCommands(
      SourceQueries queries,
      SourceAdapters adapters,
      FreshnessReader freshness,
      Set<SpaceId> spaces,
      Clock clock,
      SourceQueryJson render,
      CommandOutput out) {
    this.queries = queries;
    this.adapters = adapters;
    this.freshness = freshness;
    this.spaces = Set.copyOf(spaces);
    this.clock = clock;
    this.render = render;
    this.out = out;
  }

  /** `source list|status <id>|rejections <id> [--limit=<n>]`. */
  void source(String sub, ApplicationArguments args) {
    switch (sub) {
      case "list" -> out.write(JsJson.pretty(render.sources(queries.listSources(), adapters)));
      case "status" ->
          out.write(
              JsJson.pretty(render.status(queries.status(sourceArgument(args), clock.instant()))));
      case "rejections" -> {
        int limit =
            Math.min(
                Commands.optional(args, "limit")
                    .map(v -> positiveInteger("limit", v))
                    .orElse(REJECTIONS_DEFAULT_LIMIT),
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
  void raw(String sub, ApplicationArguments args) {
    switch (sub) {
      case "list" -> {
        RawCatalogQuery query =
            new RawCatalogQuery(
                Commands.optional(args, "limit")
                    .map(v -> positiveInteger("limit", v))
                    .orElse(RAW_DEFAULT_LIMIT),
                Commands.optional(args, "cursor").map(RawCatalogCursor::decode),
                Commands.optional(args, "source").map(SourceInstanceId::new),
                Commands.optional(args, "type"),
                Commands.optional(args, "state").map(RawState::fromWire));
        out.write(JsJson.pretty(render.page(queries.rawList(query))));
      }
      case "get" ->
          out.write(
              JsJson.pretty(
                  render.record(queries.rawGet(sourceArgument(args), positional(args, 3)))));
      default -> throw new IllegalArgumentException(Commands.USAGE);
    }
  }

  /** `status [--log=<serve.log>]`: 소스별 상태·최근 run·점유·엔진 최신성·로그 경고(launchd는 보지 않는다). */
  void overview(ApplicationArguments args) {
    Instant now = clock.instant();
    List<SourceQueries.SourceStatus> statuses =
        queries.listSources().stream()
            .map(SourceInstance::id)
            .flatMap(id -> queries.status(id, now).stream())
            .toList();
    List<SourceQueryJson.SpaceFreshness> spaceFreshness =
        spaces.stream()
            .sorted(Comparator.comparing(SpaceId::value))
            .map(space -> new SourceQueryJson.SpaceFreshness(space, freshness.read(space)))
            .toList();
    out.write(
        JsJson.pretty(
            render.overview(
                now,
                statuses,
                queries.recentRuns(OVERVIEW_RUNS),
                queries.claims(),
                spaceFreshness,
                Commands.optional(args, "log").map(QueryCommands::logProblems))));
  }

  /** 로그 파일의 `"level":"warn|error"` 줄 중 마지막 몇 줄(status.sh의 grep | tail). 없거나 못 읽으면 found=false. */
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

  private static SourceInstanceId sourceArgument(ApplicationArguments args) {
    return new SourceInstanceId(positional(args, 2));
  }

  static String positional(ApplicationArguments args, int index) {
    List<String> positional = args.getNonOptionArgs();
    if (positional.size() <= index) throw new IllegalArgumentException(Commands.USAGE);
    return positional.get(index);
  }

  /** TS positiveInteger: 안전 정수 범위의 양의 정수. */
  static int positiveInteger(String name, String raw) {
    int value;
    try {
      value = Integer.parseInt(raw);
    } catch (NumberFormatException e) {
      value = 0;
    }
    if (value <= 0)
      throw new IllegalArgumentException("--" + name + " must be a positive integer: " + raw);
    return value;
  }
}
