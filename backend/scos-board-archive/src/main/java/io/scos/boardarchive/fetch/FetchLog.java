package io.scos.boardarchive.fetch;

import java.util.*;

/** Counts every wire attempt, including robots. A snapshot can delimit one crawl run. */
public final class FetchLog {
  private long requests;
  private long permissionUses;
  private final Map<Integer, Long> byStatus = new HashMap<>();
  private Optional<Permission> permission = Optional.empty();

  public synchronized long requests() {
    return requests;
  }

  public synchronized Map<Integer, Long> byStatus() {
    return Map.copyOf(byStatus);
  }

  public synchronized Optional<Permission> robotsExceptionUsed() {
    return permission;
  }

  synchronized void attempted() {
    requests++;
  }

  synchronized void received(int status) {
    byStatus.merge(status, 1L, Long::sum);
  }

  synchronized void permitted(Permission p) {
    permission = Optional.of(p);
    permissionUses++;
  }

  public synchronized FetchLog snapshot() {
    FetchLog copy = new FetchLog();
    copy.requests = requests;
    copy.permissionUses = permissionUses;
    copy.byStatus.putAll(byStatus);
    copy.permission = permission;
    return copy;
  }

  public synchronized FetchLog since(FetchLog before) {
    FetchLog delta = new FetchLog();
    delta.requests = requests - before.requests;
    byStatus.forEach(
        (k, v) -> {
          long n = v - before.byStatus.getOrDefault(k, 0L);
          if (n > 0) delta.byStatus.put(k, n);
        });
    if (permissionUses > before.permissionUses) delta.permission = permission;
    return delta;
  }
}
