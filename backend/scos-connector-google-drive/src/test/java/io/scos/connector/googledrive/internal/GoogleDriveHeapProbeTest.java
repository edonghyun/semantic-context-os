package io.scos.connector.googledrive.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.support.ChildJvm;
import io.scos.support.ChildJvm.ProbeResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * F7 리뷰 B I1 측정 프로브(게이트 아님, perfTest): 큰 Workspace 응답(Docs) 하나를 제품 어댑터가 수집하는 데 필요한 힙을 `-Xmx`를 바꿔 가며
 * 자식 JVM({@link GoogleDriveHeapProbe})으로 잰다. 결과는 로그의 `RESULT |` 표 줄이고, 데몬 힙
 * 기본값(deploy/java/bin/scos-daemon.sh의 SCOS_DAEMON_HEAP)의 근거다. 응답은 50 MiB 상한 가까운 ASCII 문서와 한글 문서
 * 둘이다. 실행: `./gradlew perfTest --tests '*GoogleDriveHeapProbeTest'`(약 25초).
 */
@Tag("perf")
@Tag("process")
@DisplayName("Drive 큰 Workspace 응답 수집의 힙 측정(perf, 게이트 아님)")
class GoogleDriveHeapProbeTest {
  static final Logger log = LoggerFactory.getLogger(GoogleDriveHeapProbeTest.class);

  record Shape(String name, long bytes, String alphabet) {}

  static final List<Shape> SHAPES =
      List.of(
          new Shape("ASCII Docs 47.5 MB", 47_500_000L, "ascii"),
          new Shape("한글 Docs 26.7 MB", 26_700_000L, "hangul"));

  static final List<String> HEAPS = List.of("384m", "512m", "768m", "1024m");

  /** 자식 하나의 상한(hang 방지). */
  static final Duration PROCESS_LIMIT = Duration.ofMinutes(5);

  @TempDir Path logs;

  @Test
  @DisplayName("응답 모양과 힙 상한마다 수집 성공 여부를 잰다")
  void measuresHeapPerShape() throws Exception {
    // when·then
    List<ProbeResult> results = new ArrayList<>();
    log.info("RESULT | 응답 | -Xmx | 결과 | 저장 | 바이트 |");
    for (Shape shape : SHAPES)
      for (String heap : HEAPS) {
        ProbeResult r = measure(shape, heap);
        results.add(r);
        log.info(
            "RESULT | {} | {} | {} | {} | {} |",
            shape.name(),
            heap,
            outcome(r),
            r.get("stored"),
            r.get("bytes"));
      }
    assertThat(results).hasSize(SHAPES.size() * HEAPS.size());
  }

  /** 이 힙 상한의 자식 JVM 하나로 이 응답을 수집한 결과 줄. */
  ProbeResult measure(Shape shape, String heap) throws Exception {
    Path out = logs.resolve(shape.alphabet() + "-" + heap + ".log");
    Process child =
        ChildJvm.start(
            GoogleDriveHeapProbe.class,
            List.of("-Xmx" + heap),
            out,
            Long.toString(shape.bytes()),
            shape.alphabet(),
            GoogleDriveFixtures.DIR.toAbsolutePath().toString());
    if (!child.waitFor(PROCESS_LIMIT.toSeconds(), TimeUnit.SECONDS)) {
      child.destroyForcibly();
      throw new IllegalStateException("프로브가 끝나지 않았다: " + shape + " " + heap);
    }
    return ChildJvm.readResult(out);
  }

  static String outcome(ProbeResult r) {
    if (r.get("ok").equals("true")) return "통과";
    return r.get("stored").equals("none") ? "OOM" : "수집 제한";
  }
}
