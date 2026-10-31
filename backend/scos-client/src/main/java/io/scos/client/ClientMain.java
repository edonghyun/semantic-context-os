package io.scos.client;

import io.scos.client.collect.CollectionReport;
import io.scos.mcp.McpStdioServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** scos-local 진입점. 인자·환경변수를 해석하고 sync 또는 stdio MCP 수명을 실행한다. */
public final class ClientMain {
  static final int OK = 0;
  static final int USAGE = 2;
  static final int LOCK_HELD = 3;
  static final int OPEN_FAILED = 4;
  private static final Logger log = LoggerFactory.getLogger(ClientMain.class);

  private ClientMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.getenv()));
  }

  static int run(String[] args, InputStream in, OutputStream out, Map<String, String> env) {
    return run(args, in, out, env, ClientOptions.defaults());
  }

  static int run(
      String[] args,
      InputStream in,
      OutputStream out,
      Map<String, String> env,
      ClientOptions options) {
    String mode = args.length == 0 ? "mcp" : args[0];
    if (args.length > 1 || !(mode.equals("mcp") || mode.equals("sync"))) {
      log.info("사용법: scos-local [mcp|sync]");
      return USAGE;
    }
    ClientPaths paths;
    try {
      paths = ClientPaths.from(env);
    } catch (IllegalArgumentException e) {
      log.info("{}", e.getMessage());
      return USAGE;
    }
    Optional<ClientAssembly> assembly = new ClientStartup(paths, options).open();
    if (assembly.isEmpty()) return OPEN_FAILED;
    return mode.equals("sync") ? sync(assembly.get()) : mcp(assembly.get(), in, out, options);
  }

  private static int sync(ClientAssembly assembly) {
    try (assembly) {
      Optional<CollectionReport> report = assembly.collectOnce();
      if (report.isEmpty()) {
        log.info("sync: 다른 프로세스가 수집 잠금을 쥐고 있다");
        return LOCK_HELD;
      }
      log.info("sync: {}", report.get());
      return OK;
    }
  }

  private static int mcp(
      ClientAssembly assembly, InputStream in, OutputStream out, ClientOptions options) {
    McpStdioServer server = new McpStdioServer(assembly.tools(), LocalProfile.PROFILE, in, out);
    try {
      server.start();
    } catch (RuntimeException | Error failure) {
      assembly.close();
      throw failure;
    }
    try (CollectionCycles collection = CollectionCycles.start(assembly, options)) {
      try {
        server.awaitStdinEof();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        server.stop();
      }
    }
    return OK;
  }
}
