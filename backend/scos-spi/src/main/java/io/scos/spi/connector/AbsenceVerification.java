package io.scos.spi.connector;

import java.util.Set;

/** 현재 run의 동일 후보 집합에서 원격 범위 이탈을 확인한 ID. 관측을 대신하지 않는다. */
public record AbsenceVerification(
    String fingerprint, String candidateToken, Set<String> exclusions) {
  public AbsenceVerification {
    exclusions = Set.copyOf(exclusions);
  }
}
