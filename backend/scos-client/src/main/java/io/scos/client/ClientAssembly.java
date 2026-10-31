package io.scos.client;

import io.scos.client.collect.CollectionReport;
import io.scos.engine.ContextEngine;
import io.scos.mcp.McpTools;
import java.time.Clock;
import java.util.Optional;

/** 단독 클라이언트의 검색 엔진과 수집 세션을 조립하고 함께 닫는다. */
public final class ClientAssembly implements AutoCloseable {
  private final LocalEngine local;
  private final CollectionSession collection;

  private ClientAssembly(LocalEngine local, CollectionSession collection) {
    this.local = local;
    this.collection = collection;
  }

  public static ClientAssembly open(ClientPaths paths, Clock clock) {
    return open(paths, ClientOptions.defaults(clock));
  }

  static ClientAssembly open(ClientPaths paths, ClientOptions options) {
    LocalEngine local = LocalEngine.open(paths, options.clock());
    try {
      return new ClientAssembly(local, CollectionSession.indexed(paths, options, local));
    } catch (RuntimeException | Error failure) {
      local.close();
      throw failure;
    }
  }

  public ContextEngine engine() {
    return local.engine();
  }

  public McpTools tools() {
    return local.tools();
  }

  public Optional<CollectionReport> collectOnce() {
    return collection.collectOnce();
  }

  public void cancelCollection() {
    collection.cancel();
  }

  @Override
  public void close() {
    try {
      collection.close();
    } finally {
      local.close();
    }
  }
}
