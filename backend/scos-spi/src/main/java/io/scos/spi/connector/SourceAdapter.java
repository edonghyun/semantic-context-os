package io.scos.spi.connector;

import java.util.List;
import java.util.Optional;

/**
 * 소스 어댑터 포트(ADR-0018 §2). push 방식: 페이지를 만들 때마다 sink에 넘긴다 — sink가 커밋하고, 던지면 중단. 체크포인트 JSON 형식은 어댑터가
 * 소유한다(F12).
 */
public interface SourceAdapter {
  String kind();

  /** 잘못된 설정은 invalid_configuration({@link CallFailureException}). */
  void validateConfig(SourceConfig config);

  /** F5·불변식 6: 완료가 삭제 근거가 될 수 있는지. 재개(저장된 체크포인트에서 이어감)는 incremental이어야 한다. */
  CollectionCoverage coverage(
      SourceConfig config, CollectionMode mode, Optional<Checkpoint> checkpoint);

  void collect(CollectionInput input, PageSink sink);

  /** 전체 정리에 추가 검증이 필요한 범위. 런타임이 시작 시 별도로 캡처한다. */
  default Optional<SnapshotScope> snapshotVerification(SourceConfig config) {
    return Optional.empty();
  }

  /** 삭제 감지(없으면 none): 이 설정에서 원격 삭제가 tombstone이 되는 경로. 소스 목록이 보여 준다. */
  default DeletionDetection deletionDetection(SourceConfig config) {
    return DeletionDetection.NONE;
  }

  /** 소스 목록 항목 끝에 붙는 어댑터 고유 필드(예: Slack의 slackSelection). 순서대로 붙는다. 값이 JSON이 아니면 목록이 거부한다. */
  default List<ListingField> listingFields(SourceConfig config) {
    return List.of();
  }
}
