package io.scos.connector.googledrive.internal;

import io.scos.support.FakeHttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 조립 테스트의 준비 도구(TESTING §3.5): Google 서비스 다섯마다 다른 가짜 서버를 띄우고, 각 서버는 제 서비스의 요청 하나에만 답한다. 조립이 origin
 * 속성을 서비스마다 제대로 이었는지는 각 서버가 받은 경로로 본다.
 */
final class GoogleOriginServers implements AutoCloseable {
  static final Duration TIMEOUT = Duration.ofSeconds(10);
  static final String PREFIX = "scos.collection.google-drive.";

  /**
   * origin 하나: 속성, 데이터 서비스, 그 origin이 받을 요청. OAuth 토큰 끝점은 데이터 서비스가 아니라(dataService 없음) 테스트가 부르지 않고
   * 세션이 첫 데이터 호출 전에 스스로 부른다.
   */
  record Origin(String property, Optional<GoogleService> dataService, String method, String path) {
    static Origin data(String property, GoogleService service, String path) {
      return new Origin(property, Optional.of(service), "GET", path);
    }

    static Origin tokenEndpoint(String property, String path) {
      return new Origin(property, Optional.empty(), "POST", path);
    }
  }

  static final List<Origin> ORIGINS =
      List.of(
          Origin.data("drive-base-url", GoogleService.DRIVE, "/drive/v3/about"),
          Origin.data("docs-base-url", GoogleService.DOCS, "/v1/documents/d"),
          Origin.data("sheets-base-url", GoogleService.SHEETS, "/v4/spreadsheets/s"),
          Origin.data("slides-base-url", GoogleService.SLIDES, "/v1/presentations/p"),
          Origin.tokenEndpoint("oauth-base-url", "/token"));

  private final List<FakeHttpServer> servers = new ArrayList<>();

  GoogleOriginServers() {
    try {
      for (Origin o : ORIGINS) {
        FakeHttpServer server = new FakeHttpServer();
        servers.add(server);
        server.on(
            o.method(),
            o.path(),
            r ->
                FakeHttpServer.ok(
                    o.dataService().isPresent()
                        ? "{}"
                        : "{\"access_token\":\"a\",\"expires_in\":3600}"));
      }
    } catch (IOException e) {
      close();
      throw new UncheckedIOException(e);
    }
  }

  /** origin 속성 다섯이 각 가짜 서버를 가리키는 속성 값. */
  String[] propertyValues() {
    String[] values = new String[ORIGINS.size()];
    for (int i = 0; i < values.length; i++)
      values[i] = PREFIX + ORIGINS.get(i).property() + "=" + servers.get(i).baseUrl();
    return values;
  }

  /** 조립된 클라이언트로 데이터 서비스 넷을 한 번씩 부른다(첫 호출이 토큰 끝점을 부른다). */
  void callEachService(GoogleDriveApiClient client) {
    GoogleDriveSession session = client.session(GoogleDriveFixtures.credentialJson());
    for (Origin o : ORIGINS)
      o.dataService().ifPresent(service -> session.json(service, o.path(), TIMEOUT));
  }

  /** 서버마다 받은 경로(ORIGINS 순서). */
  List<List<String>> receivedPaths() {
    return servers.stream()
        .map(s -> s.received().stream().map(FakeHttpServer.Recorded::path).toList())
        .toList();
  }

  /** 서버마다 제 서비스의 경로 하나(ORIGINS 순서) — 제대로 이었을 때 receivedPaths. */
  static List<List<String>> ownPaths() {
    return ORIGINS.stream().map(o -> List.of(o.path())).toList();
  }

  @Override
  public void close() {
    servers.forEach(FakeHttpServer::close);
  }
}
