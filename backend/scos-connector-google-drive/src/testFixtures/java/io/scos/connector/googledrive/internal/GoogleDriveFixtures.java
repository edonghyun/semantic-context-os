package io.scos.connector.googledrive.internal;

import io.scos.support.FakeHttpServer.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * harness/fixtures/google-drive(TS make-google-drive-fixture.ts 생성)를 읽는다. 경로는 Gradle이 시스템 속성으로 넘긴다.
 */
public final class GoogleDriveFixtures {
  public static final ObjectMapper JSON = new ObjectMapper();
  static final Path DIR =
      Path.of(
          System.getProperty("scos.fixtures.google-drive.dir", "../harness/fixtures/google-drive"));

  private GoogleDriveFixtures() {}

  static JsonNode read(String name) {
    try {
      return JSON.readTree(Files.readString(DIR.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 가짜 Google API 데이터셋(google-drive-dataset.json). */
  public static JsonNode dataset() {
    return read("google-drive-dataset.json");
  }

  /** TS 클라이언트 표(expected-client.json). */
  static JsonNode client() {
    return read("expected-client.json");
  }

  /** TS 어댑터 단독 사례와 설정 표(expected-cases.json). */
  static JsonNode cases() {
    return read("expected-cases.json");
  }

  /** 데이터셋의 OAuth 자격 증명 JSON(file: 스킴이 가리키는 파일 내용). */
  public static String credentialJson() {
    return dataset().get("credential").toString();
  }

  /** 데이터셋 응답 하나({status, headers?, text | base64}) → 가짜 서버 응답. */
  static Response response(JsonNode reply) {
    int status = reply.get("status").asInt();
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (reply.has("headers"))
      reply
          .get("headers")
          .properties()
          .forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
    if (reply.has("base64"))
      return Response.binary(
          status, Base64.getDecoder().decode(reply.get("base64").asString()), headers);
    return new Response(status, reply.get("text").asString(), headers);
  }
}
