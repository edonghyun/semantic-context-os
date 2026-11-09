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
import io.scos.engine.EventLog;
import io.scos.engine.internal.projection.SpaceReembedding;
import io.scos.engine.internal.projection.UnitImporter;
import io.scos.engine.support.logging.EventValues;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.embedding.EmbeddingGeneration;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.SpaceId;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  /** run 실패로 끝난 수집이면 info, run 밖의 실패면 error(logging.md §2.1 예외). */
  private static final String COLLECT_FAILED = "command.collect.failed";

  /** 실패한 소스가 모두 run 실패면 info, 하나라도 run 밖의 실패면 error(logging.md §2.1 예외). */
  private static final String CYCLE_FAILED = "command.cycle.failed";

  static final String USAGE =
      "usage: admin bootstrap --email <email> --token-file <path> | import --file=<units.json> [--space=<id>] [--embedding-generation=<provider:model:dim:input-format>] | source add --id=<id> --kind=<kind> [--name=<name>] --config=<file> --credential-ref=<env:|file:|command:…> [--interval-seconds=<n>] [--snapshot-interval-seconds=<n>] [--snapshot-guard-min-active=<n>] [--snapshot-guard-max-delete-percent=<1-100>] | source accept-shrink <id> | source list | source status <id> | source access explain <documentId> [--user <id>] | source disable <id> | source enable <id> | source rejections <id> [--limit=<n>] | raw list [--limit=<n>] [--cursor=<c>] [--source=<id>] [--type=<type>] [--state=active|deleted] | raw get <source> <externalId> | status [--log=<serve.jsonl>] | collect --source=<id> [--mode=snapshot] | project | cycle | maintenance [--grace-days=<n>] [--dry-run] | reembed --space=<id> [--dry-run] | mcp | daemon";

  private final UnitJsonImport importer;
  private final SourceRegistrar registrar;
  private final CollectionRuntime collection;
  private final SourceInstanceStore sources;
  private final ProjectionRun projection;
  private final Maintenance maintenance;
  private final SpaceReembedding reembedding;
  private final ObjectMapper json;
  private final QueryCommands queries;
  private final SnapshotShrinkGuard shrinkGuard;
  private final SourceAdapters adapters;
  private final Clock clock;
  private final CommandOutput usageErrors;
  private final AdminCommands admin;
  private volatile int exitCode;

  public Commands(
      UnitJsonImport importer,
      SourceRegistrar registrar,
      CollectionRuntime collection,
      SourceInstanceStore sources,
      ProjectionRun projection,
      Maintenance maintenance,
      SpaceReembedding reembedding,
      ObjectMapper json,
      QueryCommands queries,
      SnapshotShrinkGuard shrinkGuard,
      SourceAdapters adapters,
      Clock clock,
      CommandOutput usageErrors,
      AdminCommands admin) {
    this.importer = importer;
    this.registrar = registrar;
    this.collection = collection;
    this.sources = sources;
    this.projection = projection;
    this.maintenance = maintenance;
    this.reembedding = reembedding;
    this.json = json;
    this.queries = queries;
    this.shrinkGuard = shrinkGuard;
    this.adapters = adapters;
    this.clock = clock;
    this.usageErrors = usageErrors;
    this.admin = admin;
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    CommandArguments args = new CommandArguments(arguments);
    switch (args.command()) {
      case "admin" -> exitCode = admin.run(args);
      case "import" -> importUnits(args);
      case "source" -> source(args);
      case "raw" -> queries.raw(args.positional(1), args);
      case "status" -> queries.overview(args);
      case "collect" ->
          exitCode =
              collect(new SourceInstanceId(args.required("source")), mode(args), true).failed()
                  ? 1
                  : 0;
      case "project" -> exitCode = project() ? 0 : 1;
      case "cycle" -> exitCode = cycle() ? 0 : 1;
      case "maintenance" -> maintenance(args);
      case "reembed" -> reembed(args);
      case "mcp" -> log.info("MCP 서버 시작 (stdio, 프로필 mcp: 읽기 전용)");
      case "daemon" -> log.info("수집·투영 데몬 시작 (프로필 daemon: 감독 루프, SIGTERM으로 종료)");
      default -> throw new IllegalArgumentException(USAGE);
    }
  }

  /**
   * 유닛 JSON 적재. 벡터를 동봉했으면 그 벡터의 세대를 {@code --embedding-generation}으로 밝혀야 한다 — 없으면 사용법 오류(종료 코드 2)다.
   * 사용법 오류 문장은 stderr에 그대로 쓴다. 세대·차원이 설정과 다르거나 공간 세대가 다르면(pending 포함) 유스케이스가 거부한다.
   */
  private void importUnits(CommandArguments args) throws IOException {
    Path file = Path.of(args.required("file"));
    SpaceId space = new SpaceId(args.option("space").orElse(SpaceId.LEGACY.value()));
    Optional<EmbeddingGeneration> declared =
        args.option("embedding-generation").map(EmbeddingGeneration::parse);
    UnitImporter.Imported imported;
    try {
      imported = importer.importFile(file, space, declared);
    } catch (UnitImporter.UndeclaredGenerationException e) {
      // 사용법 오류는 로그가 아니라 사람에게 보여 주는 CLI 문장이다(logging.md §6) — 문장을 바꾸지 않는다
      usageErrors.write("import: --embedding-generation is required when units carry embeddings");
      exitCode = 2;
      return;
    }
    log.info(
        "import done: units={} vectors={} space={}", imported.units(), imported.vectors(), space);
  }

  /**
   * `source add`는 등록, `disable`·`enable`은 켜고 끄기, `accept-shrink`는 축소 허용(ADR-0028), 그 밖은
   * 조회(QueryCommands).
   */
  private void source(CommandArguments args) {
    String sub = args.positional(1);
    switch (sub) {
      case "add" -> addSource(args);
      case "disable" -> toggleSource(args, false);
      case "enable" -> toggleSource(args, true);
      case "accept-shrink" -> acceptShrink(args);
      default -> queries.source(sub, args);
    }
  }

  private void toggleSource(CommandArguments args, boolean enabled) {
    SourceInstance changed =
        registrar.setEnabled(new SourceInstanceId(args.positional(2)), enabled);
    log.info("source {}: id={}", changed.enabled() ? "enabled" : "disabled", changed.id());
  }

  /** 다음 succeeded + complete_snapshot 완료 한 번이 축소 보호 없이 정리한다. 감사 이벤트는 SnapshotShrinkGuard가 남긴다. */
  private void acceptShrink(CommandArguments args) {
    SourceInstanceId source = new SourceInstanceId(args.positional(2));
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
    // 감사 이벤트(warn collection.snapshot.shrink_accepted)는 accept가 남긴다 — 여기는 명령 결과 안내라 info다
    log.info(
        "source shrink accepted: id={} (next complete snapshot reconciles once without the guard)",
        source);
  }

  private void addSource(CommandArguments args) {
    SourceInstance registered =
        registrar.register(
            new SourceInstanceId(args.required("id")),
            args.required("kind"),
            args.option("name").orElseGet(() -> args.required("id")),
            SourceConfigInput.load(json, args.required("config")),
            args.required("credential-ref"),
            new SourceSchedule(
                args.positiveSeconds("interval-seconds"),
                args.positiveSeconds("snapshot-interval-seconds")),
            shrinkOverrides(args));
    log.info("source registered: id={} kind={}", registered.id(), registered.kind());
  }

  /** 한 소스 수집의 결과. 실패 둘은 종료 코드가 같고 로그 수준만 가른다. */
  private enum Collected {
    OK,
    /** run이 오류로 끝났다(errorCount > 0) — error collection.run.failed가 이미 남았다. */
    RUN_FAILED,
    /** run 밖의 실패(예외, 이미 수집 중) — 이 명령의 error 이벤트만 남는다. */
    FAILED;

    boolean failed() {
      return this != OK;
    }
  }

  /**
   * 한 소스 수집. 이미 수집 중이면 {@code runningFails}일 때 FAILED다 — `collect` 명령은 종료 코드 1, `cycle`은 그 소스를 건너뛸
   * 뿐 실패로 세지 않는다.
   */
  private Collected collect(SourceInstanceId source, CollectionMode mode, boolean runningFails) {
    try {
      CollectionRuntime.Report report = collection.collect(source, mode, new CancellationToken());
      log.info("collect: {}", report);
      if (report.errorCount() > 0) {
        Map<String, Object> detail = EventValues.detail("status", report.status().wire());
        report.errorKind().ifPresent(kind -> detail.put("errorKind", kind));
        collectFailed(source, detail, true);
        return Collected.RUN_FAILED;
      }
      return Collected.OK;
    } catch (AlreadyRunningException e) {
      if (runningFails) {
        EventLog.error(log, "command.collect.already_running").source(source).log();
        return Collected.FAILED;
      }
      EventLog.warn(log, "command.collect.skipped").source(source).log();
      return Collected.OK;
    } catch (RuntimeException e) {
      collectFailed(
          source,
          EventValues.detail(
              "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          false);
      return Collected.FAILED;
    }
  }

  /**
   * 수집 실패 — 종료 코드 1이나 cycle의 실패 소스가 된다. run 실패 보고는 error collection.run.failed가 이미 알렸으니 info, 예외는
   * 다른 error 신호가 없으니 error다(logging.md §2.1 예외).
   */
  private static void collectFailed(
      SourceInstanceId source, Map<String, Object> detail, boolean runFailed) {
    (runFailed ? EventLog.info(log, COLLECT_FAILED) : EventLog.error(log, COLLECT_FAILED))
        .source(source)
        .detail(detail)
        .log();
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

  /**
   * 한 사이클: enabled 소스를 id 순으로 수집(소스별 격리) → due 투영. 하나라도 실패하면 종료 코드 1. 실패가 모두 run 실패면
   * command.cycle.failed는 info다 — 소스마다 error collection.run.failed가 이미 남았다.
   */
  private boolean cycle() {
    List<SourceInstanceId> failed = new ArrayList<>();
    boolean failedOutsideRun = false;
    for (SourceInstance source : sources.listEnabled()) {
      Collected collected = collect(source.id(), CollectionMode.INCREMENTAL, false);
      if (collected.failed()) failed.add(source.id());
      if (collected == Collected.FAILED) failedOutsideRun = true;
    }
    boolean projected = project();
    if (!failed.isEmpty())
      (failedOutsideRun ? EventLog.error(log, CYCLE_FAILED) : EventLog.info(log, CYCLE_FAILED))
          .detail(
              EventValues.detail(
                  "failedSources", failed.stream().map(SourceInstanceId::value).toList()))
          .log();
    return failed.isEmpty() && projected;
  }

  /** F9·F10 유지보수: blob GC(유예 일수, 기본 GracePeriod.DEFAULT)와 변경 로그 보존. --dry-run이면 지우지 않고 보고만. */
  private void maintenance(CommandArguments args) {
    GracePeriod grace =
        args.nonNegativeInteger("grace-days").map(GracePeriod::ofDays).orElse(GracePeriod.DEFAULT);
    SweepMode mode = args.has("dry-run") ? SweepMode.DRY_RUN : SweepMode.DELETE;
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

  /**
   * 공간 재임베딩(stage2 §3.3): 저장 세대가 설정 임베더와 다른(또는 pending인) 공간을 다시 임베딩하고 세대를 바꾼다. --dry-run이면 쓰지 않고
   * 계획만 한 줄로 낸다. 실패하면 pending이 남고 종료 코드 1이다 — 다시 실행하면 끝까지 간다.
   */
  private void reembed(CommandArguments args) {
    SpaceId space = new SpaceId(args.required("space"));
    if (args.has("dry-run")) {
      SpaceReembedding.Plan plan = reembedding.plan(space);
      log.info(
          "reembed plan: space={} units={} stored={} target={}",
          space.value(),
          plan.units(),
          plan.stored().orElse("none"),
          plan.target().value());
      return;
    }
    SpaceReembedding.Report report = reembedding.run(space);
    if (report.outcome() == SpaceReembedding.Outcome.REEMBEDDED)
      log.info(
          "reembed done: space={} units={} fromCache={} embedded={} generation={}",
          space.value(),
          report.units(),
          report.fromCache(),
          report.embedded(),
          report.generation().value());
    else
      log.info(
          "reembed skipped: space={} reason={} generation={}",
          space.value(),
          skipReason(report.outcome()),
          report.generation().value());
  }

  /** 이 실행이 세대를 기록하지 않고 끝난 이유 — 모두 종료 코드 0이다(쓸 것이 없거나 다른 실행이 끝냈다). */
  private static String skipReason(SpaceReembedding.Outcome outcome) {
    return switch (outcome) {
      case ALREADY_CURRENT -> "already-current";
      case EMPTY_SPACE -> "empty-space";
      case COMPLETED_BY_OTHER_RUN -> "completed-by-other-run";
      case REEMBEDDED -> throw new IllegalArgumentException("not a skip: " + outcome);
    };
  }

  private static CollectionMode mode(CommandArguments args) {
    return args.option("mode").filter("snapshot"::equals).isPresent()
        ? CollectionMode.SNAPSHOT
        : CollectionMode.INCREMENTAL;
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
  private SnapshotShrinkOverrides shrinkOverrides(CommandArguments args) {
    SnapshotShrinkOverrides existing =
        sources
            .get(new SourceInstanceId(args.required("id")))
            .map(SourceInstance::shrinkGuard)
            .orElse(SnapshotShrinkOverrides.DAEMON_DEFAULTS);
    return new SnapshotShrinkOverrides(
        args.intBetween("snapshot-guard-min-active", 1, Integer.MAX_VALUE).or(existing::minActive),
        args.intBetween("snapshot-guard-max-delete-percent", 1, 100)
            .or(existing::maxDeletePercent));
  }
}
