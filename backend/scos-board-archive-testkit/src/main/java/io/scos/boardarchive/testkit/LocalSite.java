package io.scos.boardarchive.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LocalSite implements AutoCloseable {
  private final HttpServer server;
  private final Map<String, Deque<Reply>> replies = new HashMap<>();
  private final List<String> requests = new ArrayList<>();

  private record Reply(int status, String body, Map<String, String> headers) {}

  public LocalSite() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::serve);
    server.start();
    respond("/robots.txt", 404, "");
  }

  public URI uri(String path) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + path);
  }

  public synchronized void respond(String path, int status, String body) {
    replies.put(path, new ArrayDeque<>(List.of(new Reply(status, body, Map.of()))));
  }

  public synchronized void respond(
      String path, int status, String body, Map<String, String> headers) {
    replies.put(path, new ArrayDeque<>(List.of(new Reply(status, body, headers))));
  }

  public synchronized void enqueue(String path, int status, String body) {
    replies.computeIfAbsent(path, k -> new ArrayDeque<>()).add(new Reply(status, body, Map.of()));
  }

  public synchronized List<String> requests() {
    return List.copyOf(requests);
  }

  public List<String> requestsDuring(Runnable action) {
    int before = requests().size();
    action.run();
    return List.copyOf(requests().subList(before, requests().size()));
  }

  private void serve(HttpExchange x) throws IOException {
    Reply reply;
    synchronized (this) {
      String path = x.getRequestURI().toString();
      requests.add(path);
      Deque<Reply> queue = replies.get(path);
      reply =
          queue == null || queue.isEmpty()
              ? new Reply(500, "unregistered local path", Map.of())
              : queue.size() == 1 ? queue.peek() : queue.remove();
    }
    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
    reply.headers().forEach((k, v) -> x.getResponseHeaders().set(k, v));
    x.sendResponseHeaders(reply.status(), bytes.length);
    try (var out = x.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
