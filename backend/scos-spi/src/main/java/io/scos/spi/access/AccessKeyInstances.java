package io.scos.spi.access;

import io.scos.spi.connector.SourceInstanceId;

/** 접근 키에 쓸 수 있는 원천 인스턴스를 판정하고, 신원 조회에서 제외한 인스턴스의 경고를 남긴다. */
public final class AccessKeyInstances {
  /** SourceInstanceId가 보장하는 비어 있지 않은 id에서 키 구분자와 이스케이프 문자를 거부한다. */
  public boolean admits(SourceInstanceId instance) {
    return !instance.value().contains(":") && !instance.value().contains("%");
  }

  /** 같은 정규형 판정을 적용하며, 거부할 때만 호출 어댑터의 로거에 WARNING을 남긴다. */
  public boolean admits(SourceInstanceId instance, System.Logger logger) {
    if (admits(instance)) return true;
    logger.log(System.Logger.Level.WARNING, "비정규 원천 인스턴스의 접근 키를 제외한다: " + instance.value());
    return false;
  }
}
