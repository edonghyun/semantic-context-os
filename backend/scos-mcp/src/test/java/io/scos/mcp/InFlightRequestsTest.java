package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("stdio EOF는 읽은 요청의 응답이 기록되면 풀린다")
class InFlightRequestsTest {
  @Test
  @DisplayName("값이 0인 입력 바이트도 EOF로 바꾸지 않고 그대로 넘긴다")
  void preservesZeroByte() throws Exception {
    try (var scenario = new InFlightScenario()) {
      // when
      int read = scenario.readFirstByte(0);
      // then
      assertThat(read).isZero();
      assertThat(scenario.signalled()).isFalse();
    }
  }

  @Test
  @DisplayName("빈 수신 버퍼로 읽으면 요청이나 EOF를 소비하지 않는다")
  void emptyReadDoesNotSignalEof() throws Exception {
    try (var scenario = new InFlightScenario()) {
      // when
      int read = scenario.readEmptyBufferBefore("{\"id\":1,\"method\":\"ping\"}\n");
      // then
      assertThat(read).isZero();
      assertThat(scenario.signalled()).isFalse();
    }
  }

  @Test
  @DisplayName("바이트씩 읽고 쓴 요청과 응답도 EOF 전에 flush된다")
  void drainsBytewiseStreams() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenBytewiseRequests("{\"id\":1,\"method\":\"ping\"}\n");
      scenario.startEof();
      scenario.awaitDraining();
      scenario.respondBytewise("{\"id\":1,\"result\":{}}\n");
      int eof = scenario.readEof();
      // then
      assertThat(eof).isEqualTo(-1);
      assertThat(scenario.written()).isEqualTo("{\"id\":1,\"result\":{}}\n");
    }
  }

  @Test
  @DisplayName("다른 id의 응답으로 EOF를 알리지 않는다")
  void unrelatedResponseDoesNotSignalEof() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests("{\"id\":1,\"method\":\"ping\"}\n");
      scenario.startEof();
      scenario.awaitDraining();
      scenario.respond("{\"id\":2,\"result\":{}}\n");
      // then
      assertThat(scenario.eofRemainsPending()).isTrue();
    }
  }

  @Test
  @DisplayName("응답 대기가 인터럽트되면 IOException과 인터럽트 표시를 보존한다")
  void preservesInterruptDuringDrain() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests("{\"id\":1,\"method\":\"ping\"}\n");
      // when
      boolean interrupted = scenario.interruptedReadPreservesFlag();
      // then
      assertThat(interrupted).isTrue();
    }
  }

  @Test
  @DisplayName("개행 없는 마지막 요청의 첫 EOF는 최종 종료를 알리지 않는다")
  void firstEofForUnterminatedRequestIsNotFinal() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests("{\"id\":1,\"method\":\"ping\"}");
      scenario.startEof();
      int eof = scenario.readEof();
      // then
      assertThat(eof).isEqualTo(-1);
      assertThat(scenario.signalled()).isFalse();
    }
  }

  @Test
  @DisplayName("개행 없는 마지막 요청도 응답이 기록되면 최종 EOF를 알린다")
  void drainsUnterminatedRequestAfterResponse() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenDeferredEof("{\"id\":1,\"method\":\"ping\"}");
      // when
      scenario.respond("{\"id\":1,\"result\":{}}\n");
      int eof = scenario.readEof();
      // then
      assertThat(eof).isEqualTo(-1);
      assertThat(scenario.signalled()).isTrue();
    }
  }

  @Test
  @DisplayName("읽은 두 요청 중 하나만 응답했으면 EOF를 알리지 않는다")
  void keepsEofPendingAfterFirstResponse() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests("{\"id\":1,\"method\":\"ping\"}\n{\"id\":2,\"method\":\"ping\"}\n");
      scenario.startEof();
      scenario.awaitDraining();
      // when
      scenario.respond("{\"id\":1,\"result\":{}}\n");
      // then
      assertThat(scenario.eofRemainsPending()).isTrue();
    }
  }

  @Test
  @DisplayName("서로 다른 두 요청의 응답이 모두 기록되면 EOF를 알린다")
  void drainsAfterBothResponses() throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests("{\"id\":1,\"method\":\"ping\"}\n{\"id\":2,\"method\":\"ping\"}\n");
      scenario.startEof();
      scenario.awaitDraining();
      scenario.respond("{\"id\":1,\"result\":{}}\n");
      // when
      scenario.respond("{\"id\":2,\"result\":{}}\n");
      int eof = scenario.readEof();
      // then
      assertThat(eof).isEqualTo(-1);
      assertThat(scenario.signalled()).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"method\":\"ping\"}\n",
        "{\"id\":null,\"method\":\"ping\"}\n",
        "{\"id\":1}\n",
        "[]\n",
        "broken\n",
        " \n"
      })
  @DisplayName("요청이 아닌 줄에는 응답을 기다리지 않는다")
  void ignoresNonRequests(String line) throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests(line);
      scenario.startEof();
      int eof = scenario.readEof();
      // then
      assertThat(eof).isEqualTo(-1);
      assertThat(scenario.signalled()).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"id\":1,\"method\":\"ping\"}\n",
        "{\"id\":null}\n",
        "{\"result\":{}}\n",
        "broken\n"
      })
  @DisplayName("응답이 아닌 출력 줄은 대기 중인 요청을 끝내지 않는다")
  void ignoresNonResponses(String line) throws Exception {
    try (var scenario = new InFlightScenario()) {
      scenario.givenRequests("{\"id\":1,\"method\":\"ping\"}\n");
      scenario.startEof();
      scenario.awaitDraining();
      scenario.respond(line);
      // then
      assertThat(scenario.eofRemainsPending()).isTrue();
    }
  }
}
