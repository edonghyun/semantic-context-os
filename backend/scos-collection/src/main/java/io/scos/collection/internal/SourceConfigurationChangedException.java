package io.scos.collection.internal;

import io.scos.spi.connector.RunErrorKind;

/** 수집 시작 뒤 의미 설정이 달라져 옛 실행의 쓰기를 거부했다. */
public final class SourceConfigurationChangedException extends RuntimeException
    implements RunErrorKind {
  public SourceConfigurationChangedException() {
    super("Source configuration changed during collection");
  }

  @Override
  public String runErrorKind() {
    return "source_configuration_changed";
  }
}
