package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이벤트 이름 규칙(logging.md §2.1): 제품 main 소스의 {@code EventLog} 호출은 이름을 정할 수 있고(리터럴이나 같은 파일 상수) 그 이름은
 * {@code 영역.대상.동작} 소문자 점 표기다. 호출 찾기가 {@code EventLog.<수준>(} 모양에 기대므로 {@code EventLog} 멤버의 static
 * import는 막는다(logging.md §7). 규칙 자체는 {@link EventLogCallsTest}가 본다.
 */
@DisplayName("운영 코드의 이벤트 이름은 영역.대상.동작 형식이다(logging.md §2.1)")
class EventNameRulesTest {
  /** 제품 main 소스 전체의 EventLog 호출. */
  static List<EventLogCalls.Call> productCalls() throws IOException {
    List<EventLogCalls.Call> calls = new ArrayList<>();
    for (Path file : ProductSourceTree.files())
      calls.addAll(EventLogCalls.of(file.toString(), Files.readString(file)));
    return calls;
  }

  /** 제품 main 소스 전체의 EventLog 멤버 static import. */
  static List<EventLogCalls.StaticImport> productStaticImports() throws IOException {
    List<EventLogCalls.StaticImport> imports = new ArrayList<>();
    for (Path file : ProductSourceTree.files())
      imports.addAll(EventLogCalls.staticImports(file.toString(), Files.readString(file)));
    return imports;
  }

  @Test
  @DisplayName("이름은 모두 리터럴이나 같은 파일의 상수다")
  void namesAreResolvable() throws IOException {
    // when
    List<String> unresolved =
        productCalls().stream()
            .filter(c -> c.name().isEmpty())
            .map(c -> c.where() + " " + c.argument())
            .toList();

    // then
    assertThat(unresolved).isEmpty();
  }

  @Test
  @DisplayName("이름은 모두 소문자 점 표기 세 마디다")
  void namesAreWellFormed() throws IOException {
    // when
    List<String> malformed =
        productCalls().stream()
            .filter(c -> c.name().filter(n -> !EventLogCalls.isWellFormed(n)).isPresent())
            .map(c -> c.where() + " " + c.name().orElseThrow())
            .toList();

    // then
    assertThat(malformed).isEmpty();
  }

  @Test
  @DisplayName("EventLog 멤버를 static import하지 않는다")
  void noStaticImportsOfEventLog() throws IOException {
    // when
    List<String> imports =
        productStaticImports().stream().map(i -> i.where() + " EventLog." + i.member()).toList();

    // then
    assertThat(imports).isEmpty();
  }

  @Test
  @DisplayName("제품 코드에서 EventLog 호출을 찾는다")
  void findsProductCalls() throws IOException {
    // when
    List<EventLogCalls.Call> calls = productCalls();

    // then
    assertThat(calls)
        .extracting(EventLogCalls.Call::name)
        .contains(Optional.of("collection.run.failed"), Optional.of("daemon.cycle.completed"));
  }
}
