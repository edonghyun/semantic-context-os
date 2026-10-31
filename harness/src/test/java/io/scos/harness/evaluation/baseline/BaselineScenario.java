package io.scos.harness.evaluation.baseline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 실제 명령 진입점으로 합성 자료를 측정하고 출력 파일을 관찰한다. */
final class BaselineScenario {
  private final Path out;
  private final List<String> args;

  BaselineScenario(Path dir) throws URISyntaxException {
    out = dir.resolve("output");
    args =
        new ArrayList<>(
            List.of(
                "--pack", resource("pack.json"),
                "--projects", resource("projects"),
                "--out", out.toString()));
  }

  void includeQueryText() {
    args.add("--include-query-text");
  }

  void givenPack(String pack) {
    args.set(1, pack);
  }

  void measure() throws IOException {
    var errors = new ByteArrayOutputStream();
    int code =
        BaselineMain.run(
            args.toArray(String[]::new),
            Map.of("SCOS_RG", System.getenv().getOrDefault("SCOS_RG", "rg")),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(errors, true, StandardCharsets.UTF_8));
    if (code != 0) throw new IOException("baseline 종료 " + code + ": " + errors);
  }

  String queries() throws IOException {
    return Files.readString(out.resolve("queries.jsonl"));
  }

  String summary() throws IOException {
    return Files.readString(out.resolve("summary.json"));
  }

  private static String resource(String name) throws URISyntaxException {
    return Path.of(BaselineScenario.class.getResource("/baseline/" + name).toURI()).toString();
  }
}
