package io.scos.client;

import io.scos.support.LogCapture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

final class ClientStartupScenario implements AutoCloseable {
  private final Map<String, String> environment;
  private final Path home;
  private final LogCapture startupLogs = LogCapture.of(ClientStartup.class);
  private final LogCapture cycleLogs = LogCapture.of(CollectionCycles.class);

  ClientStartupScenario(Path root) throws IOException {
    home = root.resolve("home");
    Path projects = Files.createDirectories(root.resolve("projects"));
    environment =
        Map.of(
            "SCOS_HOME",
            home.toString(),
            "SCOS_CLAUDE_PROJECTS",
            projects.toString(),
            "SCOS_QUERY_LOG",
            "");
  }

  void givenHomeIsFile() throws IOException {
    Files.writeString(home, "synthetic");
  }

  void givenUnreadableState() throws IOException {
    Files.createDirectories(home.resolve("state.sqlite"));
  }

  void runCycle() throws Exception {
    try (var session = ScosStdioSession.collected(environment)) {
      session.finish();
    }
  }

  void run() {
    ClientMain.run(
        new String[] {"sync"},
        new ByteArrayInputStream(new byte[0]),
        new ByteArrayOutputStream(),
        environment);
  }

  List<JsonNode> events(String event) {
    return event.equals("client.cycle.failed")
        ? cycleLogs.events(event)
        : startupLogs.events(event);
  }

  @Override
  public void close() {
    startupLogs.close();
    cycleLogs.close();
  }
}
