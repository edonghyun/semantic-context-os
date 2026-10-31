package io.scos.collection.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 작업을 점유한 worker(프로세스·스레드)의 식별자. */
public record OwnerId(String value) {
  public OwnerId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("ownerId must not be blank");
  }

  /** 이 프로세스: 호스트:pid(호스트 이름을 모르면 unknown-host). */
  public static OwnerId ofCurrentProcess() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "unknown-host";
    }
    return new OwnerId(host + ":" + ProcessHandle.current().pid());
  }

  @Override
  public String toString() {
    return value;
  }
}
