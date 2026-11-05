package io.scos.spi.access;

import io.scos.spi.source.SourceInstanceId;
import java.util.Locale;

/** 원천 인스턴스 안에서 식별되는 접근 키(접근 설계 §3.1). */
public record AccessKey(Kind kind, SourceInstanceId instance, String value) {
  private static final AccessKeyInstances INSTANCES = new AccessKeyInstances();

  public AccessKey {
    if (kind == null || instance == null || value == null || value.isEmpty())
      throw new IllegalArgumentException("Invalid access key");
    if (!INSTANCES.admits(instance)) throw new IllegalArgumentException("Invalid access key");
    if (kind == Kind.SCOPE && !value.equals("*"))
      throw new IllegalArgumentException("Invalid access key");
  }

  public enum Kind {
    USER,
    GROUP,
    DOMAIN,
    SCOPE
  }

  public static AccessKey parse(String text) {
    if (text == null) throw new IllegalArgumentException("Invalid access key");
    String[] parts = text.split(":", -1);
    if (parts.length != 3) throw new IllegalArgumentException("Invalid access key");
    Kind kind =
        switch (parts[0]) {
          case "user" -> Kind.USER;
          case "group" -> Kind.GROUP;
          case "domain" -> Kind.DOMAIN;
          case "scope" -> Kind.SCOPE;
          default -> throw new IllegalArgumentException("Invalid access key");
        };
    if (parts[1].isBlank()) throw new IllegalArgumentException("Invalid access key");
    String encoded = parts[2];
    AccessKey key =
        new AccessKey(
            kind, new SourceInstanceId(parts[1]), encoded.replace("%3A", ":").replace("%25", "%"));
    if (!key.toString().equals(text)) throw new IllegalArgumentException("Invalid access key");
    return key;
  }

  @Override
  public String toString() {
    return kind.name().toLowerCase(Locale.ROOT)
        + ":"
        + instance
        + ":"
        + value.replace("%", "%25").replace(":", "%3A");
  }
}
