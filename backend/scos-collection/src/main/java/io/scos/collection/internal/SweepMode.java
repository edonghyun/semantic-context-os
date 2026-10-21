package io.scos.collection.internal;

/** 유지보수 실행 방식: 지우거나(DELETE), 지울 것을 세기만 한다(DRY_RUN — 명령 `--dry-run`). */
public enum SweepMode {
  DELETE,
  DRY_RUN
}
