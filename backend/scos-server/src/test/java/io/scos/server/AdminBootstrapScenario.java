package io.scos.server;

import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.spi.access.TokenHash;
import io.scos.support.ChildJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 JAR·합성 PostgreSQL·프로세스 수명을 소유하는 bootstrap 컨텍스트. */
@Tag("db")
final class AdminBootstrapScenario implements AutoCloseable {
  private final Path directory;
  private final List<Process> processes = new ArrayList<>();
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());

  AdminBootstrapScenario(Path directory) {
    this.directory = directory;
    PostgresSupport.truncateAll();
  }

  record Running(Process process, Path stdout, Path stderr) {}

  record Finished(int exitCode, String stdout, String stderr) {}

  Running start(Path tokenFile, String email) throws Exception {
    return start("admin", "bootstrap", "--email", email, "--token-file", tokenFile.toString());
  }

  Running start(String... args) throws Exception {
    int index = processes.size();
    var stdout = directory.resolve("stdout-" + index);
    var stderr = directory.resolve("stderr-" + index);
    var process =
        ChildJvm.startJarSeparated(
            Path.of(System.getProperty("scos.boot.jar")),
            stdout,
            stderr,
            Map.of(
                "SCOS_DB_URL",
                PostgresSupport.jdbcUrl(),
                "SCOS_DB_USER",
                PostgresSupport.username(),
                "SCOS_DB_PASSWORD",
                PostgresSupport.password(),
                "GEMINI_API_KEY",
                "unused-in-test"),
            args);
    processes.add(process);
    return new Running(process, stdout, stderr);
  }

  Finished finish(Running running) throws Exception {
    if (!running.process().waitFor(Duration.ofSeconds(60).toSeconds(), TimeUnit.SECONDS))
      throw new IllegalStateException("bootstrap process did not finish");
    return new Finished(
        running.process().exitValue(),
        Files.readString(running.stdout()),
        Files.readString(running.stderr()));
  }

  Finished bootstrap(Path tokenFile) throws Exception {
    return finish(start(tokenFile, "admin@example.com"));
  }

  Finished bootstrap(Path file, boolean equals) throws Exception {
    var running =
        equals
            ? start("admin", "bootstrap", "--email= ADMIN@EXAMPLE.COM ", "--token-file=" + file)
            : start(file, " ADMIN@EXAMPLE.COM ");
    return finish(running);
  }

  Finished usageError(String kind) throws Exception {
    String[] args =
        switch (kind) {
          case "missing" -> new String[] {"admin", "bootstrap", "--email=admin@example.com"};
          case "unknown" -> new String[] {"admin", "other"};
          default -> malformedBootstrap(kind);
        };
    return finish(start(args));
  }

  private String[] malformedBootstrap(String kind) {
    String fileOption = "--token-file=" + directory.resolve("token");
    return switch (kind) {
      case "invalid-email" -> new String[] {"admin", "bootstrap", "--email=invalid", fileOption};
      case "duplicate" ->
          new String[] {
            "admin",
            "bootstrap",
            "--email=admin@example.com",
            "--email=other@example.com",
            fileOption
          };
      default ->
          new String[] {"admin", "bootstrap", "extra", "--email=admin@example.com", fileOption};
    };
  }

  long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  long administrators() {
    return jdbc.sql("SELECT count(*) FROM users WHERE role='admin' AND active")
        .query(Long.class)
        .single();
  }

  String email() {
    return jdbc.sql("SELECT email FROM users").query(String.class).single();
  }

  boolean authenticates(Path tokenFile) throws Exception {
    return new PostgresIndexConfiguration()
        .newUserTokens(PostgresSupport.dataSource(), PostgresSupport.dataSource())
        .authenticate(TokenHash.of(Files.readString(tokenFile).strip()))
        .isPresent();
  }

  void givenInactiveAdministrator() {
    jdbc.sql(
            "INSERT INTO users (id,email,active,role,created_at) VALUES ('previous','previous@example.com',false,'admin','2026-11-08T00:00:00Z')")
        .update();
  }

  void givenAuditFailure() {
    jdbc.sql(
            "ALTER TABLE audit_events ADD CONSTRAINT c13_audit_failure CHECK (action <> 'token.issue')")
        .update();
  }

  List<String> auditActions() {
    return jdbc.sql("SELECT action FROM audit_events ORDER BY id").query(String.class).list();
  }

  String auditText() {
    return jdbc.sql("SELECT detail::text FROM audit_events ORDER BY id")
        .query(String.class)
        .list()
        .toString();
  }

  @Override
  public void close() {
    processes.forEach(
        process -> {
          if (process.isAlive()) process.destroyForcibly();
        });
    jdbc.sql("ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS c13_audit_failure").update();
  }
}
