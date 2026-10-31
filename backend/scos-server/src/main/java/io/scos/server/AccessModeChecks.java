package io.scos.server;

import io.scos.boot.AccessProperties;
import io.scos.engine.support.PureFunctions;
import org.jspecify.annotations.Nullable;

/** 접근 모드에 허용되지 않은 기동 조합을 거부한다. */
@PureFunctions
final class AccessModeChecks {
  private AccessModeChecks() {}

  static void refuseStdio(AccessProperties access) {
    if (access.mode() == AccessProperties.Mode.MULTI_USER)
      throw new IllegalStateException(
          "scos.access.mode=multi-user에서는 stdio MCP를 열 수 없습니다. 데몬 HTTP MCP(사용자 토큰)를 쓰세요");
  }

  static void refuseCompacted(AccessProperties access, long compactedCount) {
    if (access.mode() == AccessProperties.Mode.MULTI_USER && compactedCount > 0)
      throw new IllegalStateException(
          "scos.access.mode=multi-user: 압축 유닛 "
              + compactedCount
              + "개가 있어 기동할 수 없습니다. docs/operations/multi-user.md의 '압축 유닛 정리' 절에 따라 삭제한 뒤 재기동하세요");
  }

  static void refuseUnsafeManagement(
      int serverPort, @Nullable Integer managementPort, @Nullable String address) {
    if (managementPort == null || (managementPort != 0 && managementPort == serverPort))
      throw new IllegalStateException(
          "multi-user HTTP는 management.server.port를 server.port와 분리해야 합니다");
    if (!"127.0.0.1".equals(address) && !"::1".equals(address))
      throw new IllegalStateException(
          "multi-user HTTP는 management.server.address를 127.0.0.1 또는 ::1로 설정해야 합니다");
  }
}
