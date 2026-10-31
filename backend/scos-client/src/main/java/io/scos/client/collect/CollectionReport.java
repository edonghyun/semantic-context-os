package io.scos.client.collect;

/**
 * 수집·투영 한 번의 결과 집계. documentsFailed = 인제스트가 격리해 대기 목록에 남긴 문서 수, failedSources = 실패해 이번 주기를 건너뛴 원천
 * 수(둘 다 다음 주기에 다시 시도한다).
 */
public record CollectionReport(
    int pages,
    int upserts,
    int deletes,
    int rejected,
    int documentsProjected,
    int documentsFailed,
    int unitsDeleted,
    int failedSources) {
  /** 아무것도 하지 않은 주기. */
  public static CollectionReport empty() {
    return new CollectionReport(0, 0, 0, 0, 0, 0, 0, 0);
  }
}
