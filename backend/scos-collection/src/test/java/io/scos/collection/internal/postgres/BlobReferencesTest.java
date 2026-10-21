package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

/** blob GC 경합 번역은 blob FK 제약 두 개의 23503만 일시 장애로 본다 — FK가 늘어도 다른 위반이 일시 장애로 숨지 않는다. */
@DisplayName("blob GC 경합 번역은 blob FK 두 개의 23503만 일시 장애로 본다")
class BlobReferencesTest {
  static final String FK_VIOLATION = "23503";
  static final String WRITTEN = "written";

  /** 서버가 보낸 것과 같은 형태의 오류(필드: 심각도 S·SQLState C·메시지 M·제약 n). */
  static DataIntegrityViolationException violation(String sqlState, String constraint) {
    ServerErrorMessage server =
        new ServerErrorMessage(
            "SERROR\0C" + sqlState + "\0Mviolates foreign key constraint\0n" + constraint + "\0");
    return new DataIntegrityViolationException("insert failed", new PSQLException(server));
  }

  @Test
  @DisplayName("원본의 blob FK 위반은 blob GC 경합이다")
  void contentBlobForeignKeyIsRace() {
    // when·then
    assertThat(
            BlobReferences.violatesBlobForeignKey(
                violation(FK_VIOLATION, "raw_items_content_blob_fk")))
        .isTrue();
  }

  @Test
  @DisplayName("격리 콘텐츠의 blob FK 위반은 blob GC 경합이다")
  void quarantinedBlobForeignKeyIsRace() {
    // when·then
    assertThat(
            BlobReferences.violatesBlobForeignKey(
                violation(FK_VIOLATION, "raw_rejections_quarantined_blob_fk")))
        .isTrue();
  }

  @Test
  @DisplayName("다른 제약의 23503은 blob GC 경합이 아니다(결함으로 전파)")
  void otherForeignKeyIsNotRace() {
    // when·then
    assertThat(
            BlobReferences.violatesBlobForeignKey(
                violation(FK_VIOLATION, "unit_bigrams_unit_seq_fkey")))
        .isFalse();
  }

  @Test
  @DisplayName("blob 제약 이름이어도 SQLState가 23503이 아니면 경합이 아니다")
  void otherSqlStateIsNotRace() {
    // when·then
    assertThat(
            BlobReferences.violatesBlobForeignKey(violation("23505", "raw_items_content_blob_fk")))
        .isFalse();
  }

  @Test
  @DisplayName("쓰기가 성공하면 그 결과를 그대로 돌려준다")
  void successfulWritePassesThrough() {
    // when·then
    assertThat(BlobReferences.translating(() -> WRITTEN)).isEqualTo(WRITTEN);
  }

  @Test
  @DisplayName("쓰기 중 blob GC 경합은 일시 장애로 바뀐다")
  void raceDuringWriteBecomesTransient() {
    // given
    DataIntegrityViolationException race = violation(FK_VIOLATION, "raw_items_content_blob_fk");

    // when·then
    assertThatThrownBy(() -> BlobReferences.translating(() -> failWith(race)))
        .isInstanceOf(TransientDataAccessResourceException.class)
        .hasCause(race);
  }

  @Test
  @DisplayName("쓰기 중 다른 무결성 위반은 그대로 전파된다")
  void otherViolationDuringWritePropagates() {
    // given
    DataIntegrityViolationException defect = violation(FK_VIOLATION, "unit_bigrams_unit_seq_fkey");

    // when·then
    assertThatThrownBy(() -> BlobReferences.translating(() -> failWith(defect))).isSameAs(defect);
  }

  static String failWith(RuntimeException error) {
    throw error;
  }
}
