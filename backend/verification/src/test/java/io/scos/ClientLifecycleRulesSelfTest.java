package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import scosrules.ClientLifecycleSamples;

@DisplayName("수명 규칙은 실패 정리와 생성자 I/O 위반을 구별한다(TESTING §4.2·B11)")
class ClientLifecycleRulesSelfTest {
  private static final JavaClasses SAMPLES =
      new ClassFileImporter().importClasses(ClientLifecycleSamples.class.getDeclaredClasses());

  @Test
  @DisplayName("순수 도우미만 부르는 생성자는 열기 메서드가 있어도 통과한다")
  void acceptsPureConstructor() {
    // given
    var sample = SAMPLES.get(ClientLifecycleSamples.PassesPureConstructor.class);

    // when
    var acquisitions = ClientLifecycleRulesTest.constructorAcquisitions(sample);

    // then
    assertThat(acquisitions).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      classes = {
        ClientLifecycleSamples.ViolatesDirectAcquisition.class,
        ClientLifecycleSamples.ViolatesHelperAcquisition.class
      })
  @DisplayName("생성자가 직접 또는 도우미를 거쳐 실행기를 열면 위반으로 보고한다")
  void rejectsConstructorAcquisition(Class<?> sample) {
    // when
    var acquisitions = ClientLifecycleRulesTest.constructorAcquisitions(SAMPLES.get(sample));

    // then
    assertThat(acquisitions)
        .extracting(call -> call.getTargetOwner().getSimpleName())
        .containsExactly("Executors");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("guardedFactories")
  @DisplayName("열기 직후 try의 실패 catch가 같은 자원을 닫고 다시 던지면 통과한다")
  void acceptsFailureCleanup(String description, String source) {
    // when
    var unguarded = FactoryFailureCleanup.unguardedOpenings(source);

    // then
    assertThat(unguarded).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unguardedFactories")
  @DisplayName("실패 catch가 같은 자원을 무조건 닫고 다시 던지지 않으면 위반이다")
  void rejectsMissingFailureCleanup(String description, String source) {
    // when
    var unguarded = FactoryFailureCleanup.unguardedOpenings(source);

    // then
    assertThat(unguarded).containsExactly(1);
  }

  @Test
  @DisplayName("주석과 문자열 안의 열기 호출은 검사하지 않는다")
  void ignoresNonCodeOpenings() {
    // given
    String source =
        """
        // SqliteIndex.open(path);
        String text = "LocalEngine.open(paths, clock)";
        """;

    // when
    var unguarded = FactoryFailureCleanup.unguardedOpenings(source);

    // then
    assertThat(unguarded).isEmpty();
  }

  private static Stream<Arguments> guardedFactories() {
    return Stream.of(
        Arguments.of(
            "색인 팩토리",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { return assemble(index); }
            catch (RuntimeException | Error failure) {
              index.close();
              throw failure;
            }
            """),
        Arguments.of(
            "엔진 팩토리",
            """
            var local = LocalEngine.open(paths, clock);
            try { return assemble(local); }
            catch (Error | RuntimeException failure) {
              // 소유권 이전 전에 실패하면 해제한다.
              local . close ( );
              throw failure;
            }
            """));
  }

  private static Stream<Arguments> unguardedFactories() {
    return Stream.concat(wrongCleanup(), unguardedAcquisition());
  }

  private static Stream<Arguments> wrongCleanup() {
    return Stream.of(
        Arguments.of(
            "성공 경로에서만 닫기",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { var result = assemble(index); index.close(); return result; }
            catch (RuntimeException | Error failure) { throw failure; }
            """),
        Arguments.of(
            "다른 자원 닫기",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { return assemble(index); }
            catch (RuntimeException | Error failure) { other.close(); throw failure; }
            """),
        Arguments.of(
            "조건부 닫기",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { return assemble(index); }
            catch (RuntimeException | Error failure) {
              if (shouldClose) index.close();
              throw failure;
            }
            """),
        Arguments.of(
            "Error 정리 누락",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { return assemble(index); }
            catch (RuntimeException failure) { index.close(); throw failure; }
            """));
  }

  private static Stream<Arguments> unguardedAcquisition() {
    return Stream.of(
        Arguments.of(
            "예외 삼키기",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { return assemble(index); }
            catch (RuntimeException | Error failure) { index.close(); return null; }
            """),
        Arguments.of(
            "try 밖에서 조립",
            """
            LocalEngine local = LocalEngine.open(paths, clock);
            prepare(local);
            try { return assemble(local); }
            catch (RuntimeException | Error failure) { local.close(); throw failure; }
            """),
        Arguments.of("소유할 변수 없이 열기", "return assemble(SqliteIndex.open(path));"),
        Arguments.of(
            "주석 속 닫기",
            """
            SqliteIndex index = SqliteIndex.open(path);
            try { return assemble(index); }
            catch (RuntimeException | Error failure) { /* index.close(); */ throw failure; }
            """));
  }
}
