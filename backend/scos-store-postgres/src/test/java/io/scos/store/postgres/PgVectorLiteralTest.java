package io.scos.store.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** pgvector 텍스트 표기 "[a,b,c]"의 쓰기·읽기. DB 없음 — 표기 자체만 본다(저장 왕복은 벡터 색인 dbTest). */
@DisplayName("pgvector 표기는 대괄호 안 쉼표로 이은 수다")
class PgVectorLiteralTest {
  @Test
  @DisplayName("쓰기는 원소를 쉼표로 이어 대괄호로 감싼다")
  void formatsCommaSeparated() {
    // when·then
    assertThat(PgVectorLiteral.format(new double[] {0.5, -1.0, 2.25})).isEqualTo("[0.5,-1.0,2.25]");
  }

  @Test
  @DisplayName("빈 벡터는 빈 대괄호다")
  void formatsEmpty() {
    // when·then
    assertThat(PgVectorLiteral.format(new double[0])).isEqualTo("[]");
  }

  @Test
  @DisplayName("읽기는 원소 앞뒤 공백을 무시하고 순서대로 수로 읽는다")
  void parsesElementsInOrder() {
    // when·then
    assertThat(PgVectorLiteral.parse("[0.5, -1,2.25 ]")).containsExactly(0.5, -1.0, 2.25);
  }

  @Test
  @DisplayName("빈 대괄호는 빈 벡터다")
  void parsesEmpty() {
    // when·then
    assertThat(PgVectorLiteral.parse("[ ]")).isEmpty();
  }
}
