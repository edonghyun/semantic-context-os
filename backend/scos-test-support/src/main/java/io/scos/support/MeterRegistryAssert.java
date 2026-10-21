package io.scos.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import org.assertj.core.api.AbstractAssert;

/**
 * 지표 이름·태그로 묻고 "있음/없음/값"을 단언한다. registry.find(...).tag(...).counter().count() 같은 탐색과 null 처리(카운터가
 * 아예 없음 vs 0)를 본문에서 지운다.
 *
 * <pre>
 * assertThatMetrics(registry)
 *     .hasCounter(ATTEMPTS, "outcome", "ok", 1)
 *     .hasNoCounter(ATTEMPTS, "outcome", "failed");
 * </pre>
 */
public final class MeterRegistryAssert extends AbstractAssert<MeterRegistryAssert, MeterRegistry> {
  private MeterRegistryAssert(MeterRegistry actual) {
    super(actual, MeterRegistryAssert.class);
  }

  /** AssertJ assertThat과 정적 import가 겹치지 않도록 이름을 따로 둔다. */
  public static MeterRegistryAssert assertThatMetrics(MeterRegistry registry) {
    return new MeterRegistryAssert(registry);
  }

  public MeterRegistryAssert hasCounter(String name, String tagKey, String tagValue, double count) {
    isNotNull();
    Counter counter =
        single(
            actual.find(name).tag(tagKey, tagValue).counters(),
            name,
            "{" + tagKey + "=" + tagValue + "}");
    if (counter == null) failWithMessage("카운터 %s{%s=%s}가 없다", name, tagKey, tagValue);
    else if (counter.count() != count)
      failWithMessage(
          "카운터 %s{%s=%s}는 %s이어야 하는데 %s이다", name, tagKey, tagValue, count, counter.count());
    return this;
  }

  /** 카운터가 등록조차 되지 않았다(0으로 등록된 것과 구분한다). */
  public MeterRegistryAssert hasNoCounter(String name, String tagKey, String tagValue) {
    isNotNull();
    Counter counter = actual.find(name).tag(tagKey, tagValue).counter();
    if (counter != null)
      failWithMessage("카운터 %s{%s=%s}가 없어야 하는데 %s로 있다", name, tagKey, tagValue, counter.count());
    return this;
  }

  /** 이 이름의 지표가 태그와 무관하게 하나도 등록되지 않았다. */
  public MeterRegistryAssert hasNoMeter(String name) {
    isNotNull();
    if (!actual.find(name).meters().isEmpty())
      failWithMessage("지표 %s가 없어야 하는데 %s개 있다", name, actual.find(name).meters().size());
    return this;
  }

  public MeterRegistryAssert hasTimerCount(String name, long count) {
    isNotNull();
    Timer timer = single(actual.find(name).timers(), name, "");
    if (timer == null) failWithMessage("타이머 %s가 없다", name);
    else if (timer.count() != count)
      failWithMessage("타이머 %s 기록 수는 %s이어야 하는데 %s이다", name, count, timer.count());
    return this;
  }

  /** 여러 태그로 고른 카운터 값. */
  public MeterRegistryAssert hasCounter(String name, Tags tags, double count) {
    isNotNull();
    Counter counter = single(actual.find(name).tags(tags).counters(), name, tags.toString());
    if (counter == null) failWithMessage("카운터 %s%s가 없다", name, tags);
    else if (counter.count() != count)
      failWithMessage("카운터 %s%s는 %s이어야 하는데 %s이다", name, tags, count, counter.count());
    return this;
  }

  /** 태그로 고른 타이머의 기록 수. */
  public MeterRegistryAssert hasTimerCount(String name, Tags tags, long count) {
    isNotNull();
    Timer timer = single(actual.find(name).tags(tags).timers(), name, tags.toString());
    if (timer == null) failWithMessage("타이머 %s%s가 없다", name, tags);
    else if (timer.count() != count)
      failWithMessage("타이머 %s%s 기록 수는 %s이어야 하는데 %s이다", name, tags, count, timer.count());
    return this;
  }

  /** 태그로 고른 게이지의 현재 값. NaN만 특별히 NaN과 같다(값을 모름 — 0과 구분한다). 그 밖에는 Double.compare라 -0.0과 0.0도 다르다. */
  public MeterRegistryAssert hasGauge(String name, Tags tags, double value) {
    isNotNull();
    Gauge gauge = single(actual.find(name).tags(tags).gauges(), name, tags.toString());
    if (gauge == null) failWithMessage("게이지 %s%s가 없다", name, tags);
    else if (Double.compare(gauge.value(), value) != 0)
      failWithMessage("게이지 %s%s는 %s이어야 하는데 %s이다", name, tags, value, gauge.value());
    return this;
  }

  /**
   * 고른 조건에 맞는 미터가 둘 이상이면 어느 계열을 단언하는지 정해지지 않는다(Micrometer Search는 그중 하나를 돌려준다) — 태그를 더 지정하라고
   * 실패한다(F15 T15.2, tr-vocab-harness High 2). 없으면 null.
   */
  private <M extends Meter> M single(Collection<M> found, String name, String selector) {
    if (found.size() > 1)
      failWithMessage(
          "지표 %s%s가 %d개 계열이다 — 태그를 더 지정한다: %s",
          name, selector, found.size(), found.stream().map(m -> m.getId().getTags()).toList());
    return found.isEmpty() ? null : found.iterator().next();
  }

  /** 태그로 고른 타이머가 등록조차 되지 않았다(한 번도 기록되지 않음). */
  public MeterRegistryAssert hasNoTimer(String name, Tags tags) {
    isNotNull();
    Timer timer = actual.find(name).tags(tags).timer();
    if (timer != null) failWithMessage("타이머 %s%s가 없어야 하는데 %s번 기록돼 있다", name, tags, timer.count());
    return this;
  }
}
