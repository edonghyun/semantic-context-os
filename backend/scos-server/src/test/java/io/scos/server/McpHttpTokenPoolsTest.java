package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("db")
@DisplayName("HTTP 토큰 인증과 사용 기록은 실제 읽기·쓰기 연결을 나눠 쓴다")
class McpHttpTokenPoolsTest {
  @Test
  @DisplayName("HTTP 읽기 엔진도 공유 정책의 도메인 키로 문서를 읽는다")
  void httpEngineUsesSharedDomainPolicy() {
    try (var http = new McpHttpTokenPoolsScenario()) {
      http.givenDomainDocument();

      var page = http.readDomainDocument();

      // then
      assertThat(page.units()).hasSize(1);
    }
  }

  @Test
  @DisplayName("사용자 토큰은 읽기 전용 풀을 거쳐 인증된다")
  void authenticationAcquiresReadConnection() {
    try (var http = new McpHttpTokenPoolsScenario()) {
      var reply = http.authenticateAndClose();

      // then
      assertThat(reply.principal()).contains(PrincipalBearerScenario.ALICE);
      assertThat(reply.readAcquisitions()).isPositive();
    }
  }

  @Test
  @DisplayName("인증된 사용 시각은 종료 때 쓰기 연결에 저장된다")
  void shutdownPersistsUsageThroughWriteConnection() {
    try (var http = new McpHttpTokenPoolsScenario()) {
      var reply = http.authenticateAndClose();

      // then
      assertThat(reply.lastUsed()).contains(PrincipalBearerScenario.NOW);
    }
  }

  @Test
  @DisplayName("컨텍스트가 종료되면 읽기 풀과 키워드 실행기를 닫는다")
  void shutdownClosesReadResources() {
    try (var http = new McpHttpTokenPoolsScenario()) {
      var reply = http.authenticateAndClose();

      // then
      assertThat(reply.readClosed()).isTrue();
      assertThat(reply.lexicalClosed()).isTrue();
    }
  }
}
