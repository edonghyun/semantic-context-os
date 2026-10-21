package io.scos.collection.internal;

import java.util.UUID;

/** 수집 run 식별자. */
public record RunId(String value) {
  public RunId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("run id must not be blank");
  }

  public static RunId random() {
    return new RunId(UUID.randomUUID().toString());
  }
}
