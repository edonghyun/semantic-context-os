package io.scos.server;

import io.scos.collection.internal.AlreadyRunningException;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.GracePeriod;
import io.scos.collection.internal.Maintenance;
import io.scos.collection.internal.SnapshotShrinkGuard;
import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceAdapters;
import io.scos.collection.internal.SourceInstance;
import io.scos.collection.internal.SourceInstanceStore;
import io.scos.collection.internal.SourceRegistrar;
import io.scos.collection.internal.SourceSchedule;
import io.scos.collection.internal.SweepMode;
import io.scos.collection.internal.projection.ProjectionRun;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import tools.jackson.databind.ObjectMapper;

/**
 * 명령 진입점(ARCHITECTURE: 진입점은 ops): 명령 → 유스케이스 호출 → 종료 코드. 실패는 종료 코드 1로 드러난다(리뷰 A-I-4). `cycle`은 소스별로
 * 격리한다 — 한 소스의 예외가 다른 소스의 수집·투영을 막지 않는다(리뷰 B-I-8). 상주 루프·종료 순서는 P5.
 */
public final class Commands implements ApplicationRunner, ExitCodeGenerator {
  private static final Logger log = LoggerFactory.getLogger(Commands.class);
  static final String USAGE =
      "usage: import --file=<units.json> [--space=<id>] | source add --id=<id> --kind=<kind> [--name=<name>] --config=<file> --credential-ref=<env:|file:|command:…> [--interval-seconds=<n>] [--snapshot-interval-seconds=<n>] [--snapshot-guard-min-active=<n>] [--snapshot-guard-max-delete-percent=<1-100>] | source accept-shrink <id> | source list | source status <id> | source disable <id> | source enable <id> | source rejections <id> [--limit=<n>] | raw list [--limit=<n>] [--cursor=<c>] [--source=<id>] [--type=<type>] [--state=active|deleted] | raw get <source> <externalId> | status [--log=<serve.log>] | collect --source=<id> [--mode=snapshot] | project | cycle | maintenance [--grace-days=<n>] [--dry-run] | mcp | daemon";

  private final UnitJsonImport importer;
  private final SourceRegistrar registrar;
  private final CollectionRuntime collection;
  private final SourceInstanceStore sources;
  private final ProjectionRun projection;
  private final Maintenance maintenance;
  private final ObjectMapper json;
  private final QueryCommands queries;
  private final SnapshotShrinkGuard shrinkGuard;
  private final SourceAdapters adapters;
  private final Clock clock;
  private volatile int exitCode;

  public Commands(
      UnitJsonImport importer,
      SourceRegistrar registrar,
      CollectionRuntime collection,
      SourceInstanceStore sources,
      ProjectionRun projection,
      Maintenance maintenance,
      ObjectMapper json,
      QueryCommands queries,
      SnapshotShrinkGuard shrinkGuard,
      SourceAdapters adapters,
      Clock clock) {
    this.importer = importer;
    this.registrar = registrar;
    this.collection = collection;
    this.sources = sources;
    this.projection = projection;
    this.maintenance = maintenance;
    this.json = json;
    this.queries = queries;
    this.shrinkGuard = shrinkGuard;
    this.adapters = adapters;
    this.clock = clock;
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    String command = args.getNonOptionArgs().isEmpty() ? "" : args.getNonOptionArgs().get(0);
    switch (command) {
      case "import" -> importUnits(args);
      case "source" -> source(args);
      case "raw" -> queries.raw(subcommand(args), args);
      case "status" -> queries.overview(args);
      case "collect" ->
          exitCode =
              collect(new SourceInstanceId(required(args, "source")), mode(args), true) ? 0 : 1;
      case "project" -> exitCode = project() ? 0 : 1;
      case "cycle" -> exitCode = cycle() ? 0 : 1;
      case "maintenance" -> maintenance(args);
      case "mcp" -> log.info("MCP 서버 시작 (stdio, 프로필 mcp: 읽기 전용)");
      case "daemon" -> log.info("수집·투영 데몬 시작 (프로필 daemon: 감독 루프, SIGTERM으로 종료)");
      default -> throw new IllegalArgumentException(USAGE);
    }
  }

  private void importUnits(ApplicationArguments args) throws java.io.IOException {
    Path file = Path.of(required(args, "file"));
    SpaceId space = new SpaceId(optional(args, "space").orElse(SpaceId.LEGACY.value()));
    var imported = importer.importFile(file, space);
    log.info(
        "import done: units={} vectors={} space={}", imported.units(), imported.vectors(), space);
  }

  /**
   * `source add`는 등록, `disable`·`enable`은 켜고 끄기, `accept-shrink`는 축소 허용(ADR-0028), 그 밖은
   * 조회(QueryCommands).
   */
  private void source(ApplicationArguments args) {
    String sub = subcommand(args);
    switch (sub) {
      case "add" -> addSource(args);
      case "disable" -> toggleSource(args, false);
      case "enable" -> toggleSource(args, true);
      case "accept-shrink" -> acceptShrink(args);
      default -> queries.source(sub, args);
    }
  }

  private void toggleSource(ApplicationArguments args, boolean enabled) {
    SourceInstance changed =
        registrar.setEnabled(new SourceInstanceId(QueryCommands.positional(args, 2)), enabled);
    log.info("source {}: id={}", changed.enabled() ? "enabled" : "disabled", changed.id());
  }

  /** 다음 succeeded + complete_snapshot 완료 한 번이 축소 보호 없이 정리한다. 감사 이벤트는 SnapshotShrinkGuard가 남긴다. */
  private void acceptShrink(ApplicationArguments args) {
    if (args.getNonOptionArgs().size() < 3) throw new IllegalArgumentException(USAGE);
    SourceInstanceId source = new SourceInstanceId(args.getNonOptionArgs().get(2));
    // 정리(complete_snapshot)가 없는 소스의 허용은 쓰일 일도 만료될 일도 없이 남는다 — 받지 않는다. 없는 소스는 accept가 거부한다
    Optional<SourceInstance> instance = sources.get(source);
    if (instance.isPresent() && !collectsCompleteSnapshots(instance.get()))
      throw new IllegalArgumentException(
          "accept-shrink applies only to sources that collect complete snapshots: "
              + source.value()
              + " ("
              + instance.get().kind()
              + ")");
    shrinkGuard.accept(source, clock.instant());
    log.warn(
        "source shrink accepted: id={} (next complete snapshot reconciles once without the guard)",
        source);
  }

  private static String subcommand(ApplicationArguments args) {
    if (args.getNonOptionArgs().size() < 2) throw new IllegalArgumentException(USAGE);
    return args.getNonOptionArgs().get(1);
  }

  private void addSource(ApplicationArguments args) {
    SourceInstance registered =
        registrar.register(
            new SourceInstanceId(required(args, "id")),
            required(args, "kind"),
            optional(args, "name").orElseGet(() -> required(args, "id")),
            SourceConfigInput.load(json, required(args, "config")),
            required(args, "credential-ref"),
            new SourceSchedule(
                positiveSeconds(args, "interval-seconds"),
                positiveSeconds(args, "snapshot-interval-seconds")),
            shrinkOverrides(args));
    log.info("source registered: id={} kind={}", registered.id(), registered.kind());
  }

  /**
   * 한 소스 수집. 실패(errorCount > 0)나 예외는 false. 이미 수집 중이면 {@code runningFails}일 때 false다 — `collect`
   * 명령은 TS처럼 종료 코드 1(TS는 "Source collection is already running"을 던진다), `cycle`은 그 소스를 건너뛸 뿐 실패로 세지
   * 않는다.
   */
  private boolean collect(SourceInstanceId source, CollectionMode mode, boolean runningFails) {
    try {
      CollectionRuntime.Report report = collection.collect(source, mode, new CancellationToken());
      log.info("collect: {}", report);
      if (report.errorCount() > 0) {
        log.error(
            "collect failed: source={} status={} kind={}",
            source,
            report.status().wire(),
            report.errorKind().orElse("?"));
        return false;
      }
      return true;
    } catch (AlreadyRunningException e) {
      if (runningFails) {
        log.error("collect failed: {}", e.getMessage());
        return false;
      }
      log.warn("collect skipped (already running): {}", source);
      return true;
    } catch (RuntimeException e) {
      log.error("collect failed: source={} error={}", source, e.getMessage());
      return false;
    }
  }

  /** due 투영 요청 처리. FAILED가 하나라도 있으면 false(SKIPPED_RUNNING은 실패가 아니다). */
  private boolean project() {
    boolean ok = true;
    for (ProjectionRun.Report report : projection.runDue(CancellationToken.none())) {
      log.info("project: {}", report);
      if (report.outcome() == ProjectionRun.Outcome.FAILED) ok = false;
    }
    return ok;
  }

  /** 한 사이클: enabled 소스를 id 순으로 수집(소스별 격리) → due 투영. 하나라도 실패하면 종료 코드 1. */
  private boolean cycle() {
    List<SourceInstanceId> failed = new ArrayList<>();
    for (SourceInstance source : sources.listEnabled())
      if (!collect(source.id(), CollectionMode.INCREMENTAL, false)) failed.add(source.id());
    boolean projected = project();
    if (!failed.isEmpty()) log.error("cycle: collection failed for {}", failed);
    return failed.isEmpty() && projected;
  }

  /** F9·F10 유지보수: blob GC(유예 일수, 기본 GracePeriod.DEFAULT)와 변경 로그 보존. --dry-run이면 지우지 않고 보고만. */
  private void maintenance(ApplicationArguments args) {
    GracePeriod grace =
        optional(args, "grace-days").map(GracePeriod::parseDays).orElse(GracePeriod.DEFAULT);
    SweepMode mode = args.containsOption("dry-run") ? SweepMode.DRY_RUN : SweepMode.DELETE;
    Maintenance.Report report = maintenance.run(grace, mode);
    log.info(
        "maintenance: graceDays={} mode={} blobs scanned={} deleted={} retainedByReference={} retainedByGrace={} bytesFreed={} prunedChanges={}",
        grace.days(),
        mode,
        report.blobs().scanned(),
        report.blobs().deleted(),
        report.blobs().retainedByReference(),
        report.blobs().retainedByGrace(),
        report.blobs().bytesFreed(),
        report.prunedChanges());
  }

  private static CollectionMode mode(ApplicationArguments args) {
    return optional(args, "mode").filter("snapshot"::equals).isPresent()
        ? CollectionMode.SNAPSHOT
        : CollectionMode.INCREMENTAL;
  }

  /** 선택 옵션 하나(양의 정수 초, TS positiveInteger). 없으면 empty. */
  private static Optional<Duration> positiveSeconds(ApplicationArguments args, String name) {
    Optional<String> value = optional(args, name);
    if (value.isEmpty()) return Optional.empty();
    String raw = value.get();
    long seconds;
    try {
      seconds = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      seconds = 0;
    }
    if (seconds <= 0)
      throw new IllegalArgumentException("--" + name + " must be a positive integer: " + raw);
    return Optional.of(Duration.ofSeconds(seconds));
  }

  /** 스냅샷 모드에서 complete_snapshot으로 끝날 수 있는 소스인가(어댑터가 모드·설정으로 정한다). */
  private boolean collectsCompleteSnapshots(SourceInstance instance) {
    return adapters
        .forKind(instance.kind())
        .map(a -> a.coverage(instance.config(), CollectionMode.SNAPSHOT, Optional.empty()))
        .filter(CollectionCoverage.COMPLETE_SNAPSHOT::equals)
        .isPresent();
  }

  /**
   * 소스별 축소 보호 기준(ADR-0028). 옵션을 주지 않은 값은 이미 등록된 값을 유지한다 — `source add`를 주기·설정만 바꾸려고 다시 실행해도 소스별 기준이
   * 기본값으로 돌아가지 않게.
   */
  private SnapshotShrinkOverrides shrinkOverrides(ApplicationArguments args) {
    SnapshotShrinkOverrides existing =
        sources
            .get(new SourceInstanceId(required(args, "id")))
            .map(SourceInstance::shrinkGuard)
            .orElse(SnapshotShrinkOverrides.DAEMON_DEFAULTS);
    return new SnapshotShrinkOverrides(
        boundedInteger(args, "snapshot-guard-min-active", 1, Integer.MAX_VALUE)
            .or(existing::minActive),
        boundedInteger(args, "snapshot-guard-max-delete-percent", 1, 100)
            .or(existing::maxDeletePercent));
  }

  /** 선택 옵션 하나(min 이상 max 이하 정수). 없으면 empty. */
  private static Optional<Integer> boundedInteger(
      ApplicationArguments args, String name, int min, int max) {
    Optional<String> value = optional(args, name);
    if (value.isEmpty()) return Optional.empty();
    String raw = value.get();
    long parsed;
    try {
      parsed = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      parsed = Long.MIN_VALUE;
    }
    if (parsed < min || parsed > max)
      throw new IllegalArgumentException(
          "--"
              + name
              + " must be "
              + (max == Integer.MAX_VALUE
                  ? "a positive integer"
                  : "an integer between " + min + " and " + max)
              + ": "
              + raw);
    return Optional.of((int) parsed);
  }

  /** 선택 옵션의 값. 옵션 없이는 empty, 값 없이(`--name`) 주면 사용법과 함께 실패한다. */
  static Optional<String> optional(ApplicationArguments args, String name) {
    if (!args.containsOption(name)) return Optional.empty();
    List<String> values = args.getOptionValues(name);
    if (values.isEmpty() || values.getFirst().isEmpty())
      throw new IllegalArgumentException("--" + name + " needs a value. " + USAGE);
    return Optional.of(values.getFirst());
  }

  private static String required(ApplicationArguments args, String name) {
    if (!args.containsOption(name) || args.getOptionValues(name).isEmpty())
      throw new IllegalArgumentException("--" + name + " 가 필요합니다. " + USAGE);
    return args.getOptionValues(name).get(0);
  }
}
