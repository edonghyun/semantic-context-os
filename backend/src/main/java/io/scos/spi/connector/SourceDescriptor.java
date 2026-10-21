package io.scos.spi.connector;

/** 커넥터가 보는 소스: 등록된 소스에서 런타임 필드(켜짐·주기·축소 보호·시각)를 뺀 다섯 필드. */
public record SourceDescriptor(
    SourceInstanceId id,
    String kind,
    String displayName,
    SourceConfig config,
    String credentialRef) {
  public SourceDescriptor {
    displayName = displayName == null ? "" : displayName;
  }
}
