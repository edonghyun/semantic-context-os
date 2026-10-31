package io.scos;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * 제품 패키지 접두어 → 모듈 표 하나. 클래스는 가장 긴 접두어의 모듈에 속한다. 모듈 위치 규칙(ModuleLocationRulesTest)과 internal 경계
 * 규칙(PublicBoundaryRulesTest R3)이 같이 쓴다 — 모듈을 더하면 여기 한 곳에 적는다.
 */
final class ProductModules {
  static final Map<String, String> MODULE_OF_PACKAGE =
      Map.ofEntries(
          Map.entry("io.scos.spi", "scos-spi"),
          Map.entry("io.scos.engine", "scos-engine"),
          Map.entry("io.scos.store.memory", "scos-store-memory"),
          Map.entry("io.scos.store.postgres", "scos-store-postgres"),
          Map.entry("io.scos.store.sqlite", "scos-store-sqlite"),
          Map.entry("io.scos.client", "scos-client"),
          Map.entry("io.scos.collection", "scos-collection"),
          Map.entry("io.scos.connector.slack", "scos-connector-slack"),
          Map.entry("io.scos.connector.notion", "scos-connector-notion"),
          Map.entry("io.scos.connector.jira", "scos-connector-jira"),
          Map.entry("io.scos.connector.googledrive", "scos-connector-google-drive"),
          Map.entry("io.scos.connector.tiro", "scos-connector-tiro"),
          Map.entry("io.scos.provider.gemini", "scos-provider-gemini"),
          Map.entry("io.scos.provider.openai", "scos-provider-openai-compatible"),
          Map.entry("io.scos.mcp", "scos-mcp"),
          Map.entry("io.scos.boot", "scos-spring-boot-starter"),
          Map.entry("io.scos.server", "scos-server"),
          Map.entry("io.scos.harness", "scos-server"));

  private ProductModules() {}

  /** 가장 긴 접두어의 모듈. 표에 없는 패키지는 empty. */
  static Optional<String> moduleOf(String packageName) {
    return MODULE_OF_PACKAGE.keySet().stream()
        .filter(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."))
        .max(Comparator.comparingInt(String::length))
        .map(MODULE_OF_PACKAGE::get);
  }
}
