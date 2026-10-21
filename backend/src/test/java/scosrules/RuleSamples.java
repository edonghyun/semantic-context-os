package scosrules;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.support.ChildJvm;
import io.scos.support.Eventually;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.BDDAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 테스트 코드 규칙(io.scos.TestCodeRulesTest)이 실제로 잡는지 보는 표본(F15 T15.2). 패키지가 io.scos 밖이라 본 규칙의 대상이 아니고,
 * 표본은 모두 abstract라 JUnit이 실행하지 않는다. 이름이 곧 기대다: Violates…는 규칙에 걸려야 하고 Passes…는 걸리지 않아야 한다. 표본 본문은
 * 규칙이 보는 모양 그대로 둔다 — 주석·제목은 census(스펙 모양)용이다.
 */
@DisplayName("테스트 코드 규칙의 위반·통과 표본")
public final class RuleSamples {
  private RuleSamples() {}

  static final Duration NAMED = Duration.ZERO;

  abstract static class ViolatesFourAssertions {
    @Test
    void four() {
      // then
      assertThat(1).isPositive();
      assertThat(2).isPositive();
      assertThat(3).isPositive();
      assertThat(4).isPositive();
    }
  }

  abstract static class PassesThreeAssertions {
    @Test
    void three() {
      // then
      assertThat(1).isPositive();
      assertThat(2).isPositive();
      assertThat(3).isPositive();
    }
  }

  abstract static class ViolatesAssertionsInHelper {
    @Test
    void viaHelper() {
      // then
      assertThat(1).isPositive();
      threeMore();
    }

    void threeMore() {
      assertThat(2).isPositive();
      assertThat(3).isPositive();
      assertThat(4).isPositive();
    }
  }

  abstract static class ViolatesAssertionsInConstructor {
    static final class Checker {
      Checker() {
        assertThat(1).isPositive();
        assertThat(2).isPositive();
        assertThat(3).isPositive();
        assertThat(4).isPositive();
      }
    }

    @Test
    void viaConstructor() {
      // then
      new Checker();
    }
  }

  /** assert…로 시작하는 도우미는 단언 하나로 센다(TESTING §8) — 안의 단언 다섯을 세지 않는다. */
  abstract static class PassesAssertHelperCountsOne {
    static void assertFive() {
      assertThat(1).isPositive();
      assertThat(2).isPositive();
      assertThat(3).isPositive();
      assertThat(4).isPositive();
      assertThat(5).isPositive();
    }

    @Test
    void named() {
      // then
      assertFive();
      assertFive();
      assertFive();
    }
  }

  abstract static class ViolatesThreadSleep {
    void waits() throws InterruptedException {
      Thread.sleep(1);
    }
  }

  abstract static class ViolatesTimeUnitSleep {
    void waits() throws InterruptedException {
      TimeUnit.MILLISECONDS.sleep(1);
    }
  }

  abstract static class ViolatesDurationLiteral {
    @Test
    void literal() {
      // then
      assertThat(Duration.ofSeconds(1)).isPositive();
    }
  }

  abstract static class PassesNamedDuration {
    @Test
    void named() {
      // then
      assertThat(NAMED).isZero();
    }
  }

  abstract static class ViolatesDisabledReason {
    @Test
    @Disabled("나중에 고친다")
    void later() {
      // then
    }
  }

  abstract static class PassesDisabledSpec {
    @Test
    @Disabled("spec: 아직 구현하지 않은 계약")
    void spec() {
      // then
    }
  }

  abstract static class PassesDisabledQuarantine {
    @Test
    @Disabled("격리: 간헐 실패 — #12, 만료 P3")
    void quarantined() {
      // then
    }
  }

  abstract static class ViolatesMissingDisplayName {
    @Test
    void untitled() {
      // then
      assertThat(1).isPositive();
    }
  }

  abstract static class PassesDisplayName {
    @Test
    @DisplayName("제목이 있다")
    void titled() {
      // then
      assertThat(1).isPositive();
    }
  }

  abstract static class ViolatesLongDisplayName {
    @Test
    @DisplayName("이 제목은 한 문장이라고 하기에는 너무 길어서 육십 자를 분명히 넘기므로 제목 길이 규칙에 반드시 걸려야 하는 표본 문장이다")
    void longTitle() {
      // then
      assertThat(1).isPositive();
    }
  }

  abstract static class ViolatesJUnitAssertions {
    @Test
    void junit() {
      // then
      Assertions.assertTrue(true);
    }
  }

  abstract static class ViolatesBddAssertions {
    @Test
    void bdd() {
      // then
      BDDAssertions.then(1).isPositive();
    }
  }

  abstract static class ViolatesStdOut {
    void prints() {
      System.out.println("result");
    }
  }

  /** 자식 프로세스 진입점(*Probe)의 RESULT 줄은 허용된다. */
  abstract static class PassesResultLineProbe {
    void prints() {
      System.out.println("RESULT done");
    }
  }

  abstract static class ViolatesLatchInTest {
    @Test
    void latch() throws InterruptedException {
      // when
      new CountDownLatch(0).await();
    }
  }

  /** 동기화는 컨텍스트(테스트가 아닌 메서드)가 소유한다 — 규칙은 도우미를 따라가지 않는다. */
  abstract static class PassesLatchInContext {
    static CountDownLatch opened() {
      return new CountDownLatch(0);
    }

    @Test
    void latch() throws InterruptedException {
      // when
      opened().await();
    }
  }

  abstract static class ViolatesMeterLookup {
    final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void lookup() {
      // then
      assertThat(registry.find("x").counter()).isNull();
    }
  }

  abstract static class PassesMeterRegistryAssert {
    final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void named() {
      // then
      assertThatMetrics(registry).hasNoMeter("x");
    }
  }

  abstract static class ViolatesAwaitility {
    void polls() {
      Awaitility.await().until(() -> true);
    }
  }

  abstract static class ViolatesDirectAwaitCondition {
    void waits() throws InterruptedException {
      ChildJvm.awaitCondition("바로 참", () -> true, 1);
    }
  }

  abstract static class PassesEventually {
    void waits() {
      Eventually.await("바로 참", () -> true, NAMED);
    }
  }
}
