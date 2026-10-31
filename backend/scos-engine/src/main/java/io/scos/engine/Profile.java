package io.scos.engine;

import java.time.Instant;
import java.util.Objects;

/** 검색 프로필: 시간을 어떻게 다룰지. AUTO는 질의 문구와 호출 종류가 정한다. */
public sealed interface Profile permits Profile.Auto, Profile.Current, Profile.AsOf {
  Profile AUTO = new Auto();
  Profile CURRENT = new Current();

  /** 질의 문구로 날짜 가산 여부를 정한다(assemble은 항상 가산). */
  record Auto() implements Profile {}

  /** 관련 결과를 수집 시각 최신순으로 반환한다(CONTRACTS B12·C6). */
  record Current() implements Profile {}

  /** 특정 시점 기준 조회. 아직 지원하지 않아 InvalidQueryException(field "profile")으로 거절한다. */
  record AsOf(Instant at) implements Profile {
    public AsOf {
      Objects.requireNonNull(at, "at");
    }
  }
}
