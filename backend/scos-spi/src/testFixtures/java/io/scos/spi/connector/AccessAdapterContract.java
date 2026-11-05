package io.scos.spi.connector;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.spi.access.DocumentAcl.Status;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지원 커넥터가 상속하는 접근 수집 계약. Scenario는 첫 대상의 공급자 응답만 실패로 만들고 뒤 대상은 정상 응답으로 준비한다. 선정 대상 순서와 실제 어댑터
 * collectAccess 호출은 Scenario가 소유한다.
 */
@DisplayName("접근 어댑터는 대상 단위 실패를 격리한다")
public abstract class AccessAdapterContract {
  public interface Scenario {
    void failingTargetBeforeHealthyTarget();

    List<AclPage> collect();

    String failingTargetId();

    String healthyTargetId();
  }

  protected abstract Scenario newScenario();

  private Scenario scenario;

  @BeforeEach
  void prepareFailedThenHealthyTarget() {
    scenario = newScenario();
    scenario.failingTargetBeforeHealthyTarget();
  }

  @Test
  @DisplayName("실패한 대상은 FAILED 빈 키 관측으로 반환한다")
  void failedTargetIsObservedInsteadOfThrowing() {
    var pages = scenario.collect();
    // then
    assertThat(
            pages.stream()
                .flatMap(page -> page.acls().entrySet().stream())
                .filter(entry -> entry.getKey().equals(scenario.failingTargetId()))
                .map(entry -> entry.getValue())
                .toList())
        .singleElement()
        .isInstanceOfSatisfying(
            RawAcl.Observed.class,
            acl -> {
              assertThat(acl.status()).isEqualTo(Status.FAILED);
              assertThat(acl.keys()).isEmpty();
            });
  }

  @Test
  @DisplayName("첫 대상 실패 뒤의 정상 대상도 OK 관측으로 반환한다")
  void targetFailureDoesNotStarveLaterTarget() {
    var pages = scenario.collect();
    // then
    assertThat(
            pages.stream()
                .flatMap(page -> page.acls().entrySet().stream())
                .filter(entry -> entry.getKey().equals(scenario.healthyTargetId()))
                .map(entry -> entry.getValue())
                .toList())
        .singleElement()
        .isInstanceOfSatisfying(
            RawAcl.Observed.class, acl -> assertThat(acl.status()).isEqualTo(Status.OK));
  }
}
