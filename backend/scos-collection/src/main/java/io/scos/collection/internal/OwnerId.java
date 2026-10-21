package io.scos.collection.internal;

/** 작업을 점유한 worker(프로세스·스레드)의 식별자. */
public record OwnerId(String value) {
  public OwnerId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("ownerId must not be blank");
  }

  @Override
  public String toString() {
    return value;
  }
}
