package io.scos.collection.internal;

import java.util.Optional;

/**
 * run이 어떻게 끝났는가: 끝난 상태, 오류 수, 오류 종류와 요약. 요약은 {@link #SUMMARY_CHARS}자(UTF-16 단위)에서 자른다 — 저장·로그 양쪽의
 * 상한이다.
 */
public record RunOutcome(
    RunStatus status, int errorCount, Optional<String> errorKind, Optional<String> errorSummary) {
  public static final int SUMMARY_CHARS = 500;

  public RunOutcome {
    if (status == RunStatus.RUNNING)
      throw new IllegalArgumentException("completion status must be terminal");
    errorKind = errorKind == null ? Optional.empty() : errorKind;
    errorSummary =
        errorSummary == null ? Optional.empty() : errorSummary.map(RunOutcome::truncated);
  }

  /** 끝까지 걸었다. 거부 항목이 있었으면 succeeded_with_rejections. */
  public static RunOutcome succeeded(int rejected) {
    return clean(rejected > 0 ? RunStatus.SUCCEEDED_WITH_REJECTIONS : RunStatus.SUCCEEDED);
  }

  /** 오류 없이 이 상태로 끝났다. */
  public static RunOutcome clean(RunStatus status) {
    return new RunOutcome(status, 0, Optional.empty(), Optional.empty());
  }

  /** 오류 하나로 이 상태가 됐다. */
  public static RunOutcome failed(RunStatus status, String kind, String summary) {
    return new RunOutcome(status, 1, Optional.of(kind), Optional.of(summary));
  }

  /** 수집이 도중에 던졌다: 취소면 오류 없는 cancelled, 페이지를 하나라도 반영했으면 partially_succeeded, 아니면 failed. */
  public static RunOutcome interrupted(
      boolean cancelled, int pagesApplied, String kind, String summary) {
    if (cancelled) return clean(RunStatus.CANCELLED);
    return failed(
        pagesApplied > 0 ? RunStatus.PARTIALLY_SUCCEEDED : RunStatus.FAILED, kind, summary);
  }

  /** 같은 상태·오류 수로, 오류 종류와 요약만 이것으로 바꾼다(스냅샷 축소 보호처럼 성공에 붙는 표시). */
  RunOutcome markedAs(String kind, String summary) {
    return new RunOutcome(status, errorCount, Optional.of(kind), Optional.of(summary));
  }

  private static String truncated(String summary) {
    return summary.length() > SUMMARY_CHARS ? summary.substring(0, SUMMARY_CHARS) : summary;
  }
}
