package io.scos.spi.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 공간 메타 포트 계약(ADR-0018 §6, CONTRACTS G9·G6): 임베딩 세대·소스 커서를 공간별 키로 둔다. 메모리 대역과 PostgreSQL 어댑터가 같이
 * 지킨다 — DocumentIngestorTest가 메모리 대역에서 읽은 세대·커서로 판정하므로(tr-projection-engine High 3).
 */
@DisplayName("공간 메타는 공간별 키 하나에 값 하나를 둔다(ADR-0018 §6)")
public abstract class SpaceMetaContract {
  protected static final SpaceId A = new SpaceId("a");
  protected static final SpaceId B = new SpaceId("b");

  protected SpaceMeta meta;

  protected abstract SpaceMeta newMeta();

  @BeforeEach
  void setUpContract() {
    meta = newMeta();
  }

  @Test
  @DisplayName("쓴 적 없는 키는 비어 있다")
  void missingKeyIsEmpty() {
    // when
    var value = meta.get(A, "embedding:version");

    // then
    assertThat(value).isEmpty();
  }

  @Nested
  @DisplayName("공간 a에 embedding:version=v1을 쓴 상태에서")
  class AfterWrite {
    @BeforeEach
    void givenWritten() {
      meta.set(A, "embedding:version", "v1");
    }

    /** 부재를 보기 전에 쓴 값이 제자리에서 보이는지 확보한다(TESTING §8) — set이 아무것도 안 하는 구현을 거른다. */
    void givenVisibleInA() {
      if (!meta.get(A, "embedding:version").equals(Optional.of("v1")))
        throw new IllegalStateException("given 준비 실패: 공간 a의 embedding:version이 v1이어야 한다");
    }

    @Test
    @DisplayName("같은 공간에서 읽으면 v1이다")
    void readsBack() {
      // when
      var value = meta.get(A, "embedding:version");

      // then
      assertThat(value).contains("v1");
    }

    @Test
    @DisplayName("같은 키에 v2를 쓰면 덮어쓴다")
    void overwrites() {
      // when
      meta.set(A, "embedding:version", "v2");

      // then
      assertThat(meta.get(A, "embedding:version")).contains("v2");
    }

    @Test
    @DisplayName("다른 공간의 같은 키는 보이지 않는다")
    void isolatedBySpace() {
      // given
      givenVisibleInA();

      // when
      var value = meta.get(B, "embedding:version");

      // then
      assertThat(value).isEmpty();
    }

    @Test
    @DisplayName("같은 공간의 다른 키는 보이지 않는다")
    void isolatedByKey() {
      // given
      givenVisibleInA();

      // when
      var value = meta.get(A, "cursor:slack-main");

      // then
      assertThat(value).isEmpty();
    }
  }
}
