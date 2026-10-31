package io.scos.client.collect;

/** 한 수집기의 실행과 취소. 취소 후 실행은 새 수집·투영 작업을 시작하지 않는다. */
public interface CollectionTask {
  CollectionReport runOnce();

  CollectionReport fullReproject();

  void cancel();
}
