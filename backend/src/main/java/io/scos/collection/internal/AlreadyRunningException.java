package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;

/** 같은 소스의 live 점유가 있다 — 이번 수집은 시작하지 않는다(F6 "already running"). */
public final class AlreadyRunningException extends RuntimeException {
  public AlreadyRunningException(SourceInstanceId source) {
    super("Source collection is already running: " + source.value());
  }
}
