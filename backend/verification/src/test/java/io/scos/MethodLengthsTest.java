package io.scos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("메서드 길이는 본문의 코드 줄을 메서드·생성자마다 센다")
class MethodLengthsTest {
  @Test
  @DisplayName("빈 줄과 주석만 있는 줄은 세지 않는다")
  void countsCodeLinesOnly() {
    // when
    Map<String, Integer> lengths =
        MethodLengths.of(
            """
            class S {
              void run() {
                a();

                // 설명
                /* 블록
                   주석 */
                b("{");
                c();
              }
            }
            """);

    // then
    assertThat(lengths).containsExactly(Map.entry("S.run()", 3));
  }

  @Test
  @DisplayName("제어 블록·람다·배열 초기화는 메서드가 아니고 익명 클래스 메서드는 따로 센다")
  void skipsBlocksThatAreNotMethods() {
    // when
    Map<String, Integer> lengths =
        MethodLengths.of(
            """
            class S {
              void run() {
                if (x) {
                  a();
                }
                Runnable r = () -> {
                  b();
                };
                new Thread() {
                  public void run() {
                    c();
                  }
                };
                int[] n = new int[] {1};
              }
            }
            """);

    // then
    assertThat(lengths).containsOnlyKeys("S.run()", "S.run()#2");
  }

  @Test
  @DisplayName("열쇠는 감싼 타입·이름·매개변수 타입이고 throws를 건너뛴다")
  void keysByEnclosingTypeAndParameterTypes() {
    // when
    Map<String, Integer> lengths =
        MethodLengths.of(
            """
            class S {
              S(int a) throws IOException {
                a();
              }

              <T> List<T> f(Map<String, T> m, final @Nullable String... xs) throws A, B {
                a();
              }

              record R(int a) {
                int twice() {
                  return a * 2;
                }
              }
            }
            """);

    // then
    assertThat(lengths).containsOnlyKeys("S.S(int)", "S.f(Map<String, T>, String...)", "R.twice()");
  }

  @Test
  @DisplayName("본문이 있는 enum 상수는 메서드가 아니다")
  void skipsEnumConstantBodies() {
    // when
    Map<String, Integer> lengths =
        MethodLengths.of(
            """
            enum E {
              A(1) {
                int f() {
                  return 1;
                }
              };

              E(int x) {
                a();
              }
            }
            """);

    // then
    assertThat(lengths).containsOnlyKeys("E.f()", "E.E(int)");
  }
}
