package io.scos.harness.cassette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 카세트 미스 로그(JSONL, 서버 프로세스의 shim이 한 줄씩 쓴다: {"kind","sha256","text"}). 하네스는 호출이 끝날 때마다 이 파일을 읽어 미스를
 * **카운터**로 판정한다 — 응답이 정상처럼 보여도(제품 fallback) 미스가 있으면 실패다. 본문(text)은 보고에 싣지 않는다.
 */
public final class MissLog {
  public record Miss(String kind, String sha256) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final Path path;

  public MissLog(Path path) {
    this.path = path;
  }

  public Path path() {
    return path;
  }

  public List<Miss> read() throws IOException {
    if (!Files.exists(path)) return List.of();
    List<Miss> out = new ArrayList<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;
      JsonNode n = MAPPER.readTree(line);
      out.add(new Miss(n.path("kind").asText("?"), n.path("sha256").asText("")));
    }
    return out;
  }

  public int count() throws IOException {
    return read().size();
  }
}
