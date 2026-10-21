package io.scos.engine.internal.projection;

/** DocumentIngestor 단위·DB 테스트가 함께 쓰는 표본 문서 본문(파이프라인 800토큰 분할 기준 유닛 수를 적었다). */
final class IngestSamples {
  /** 유닛 2개: "결제 장애"와 "후속" 절. */
  static final String BODY_A =
      "# 결제 장애\n\n결제 장애의 원인은 커넥션 풀 고갈이었고 대응은 풀 크기 상향과 알림 추가였다.\n\n## 후속\n\n후속 조치로 모니터링 지표를 추가하고 담당자를 지정했다. 재발 방지 대책을 문서화했다.\n";

  /** 유닛 1개. */
  static final String BODY_B = "# 온보딩\n\n온보딩 절차는 계정 발급과 권한 신청 두 단계로 이루어진다. 각 단계는 담당자가 승인한다.\n";

  private IngestSamples() {}
}
