package harnessrules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.assertj.core.api.BDDAssertions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * harness 테스트 코드 규칙(io.scos.harness.TestCodeRulesTest)이 실제로 잡는지 보는 표본(F15 T15.2). backend
 * scosrules.RuleSamples와 같은 모양이다 — 두 단언 셈 사본이 갈라지면 한쪽 자체 테스트가 실패한다. io.scos.harness 밖이라 본 규칙의 대상이
 * 아니고, abstract라 실행되지 않는다.
 */
@DisplayName("harness 테스트 코드 규칙의 위반·통과 표본")
public final class RuleSamples {
  private RuleSamples() {}

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
}
