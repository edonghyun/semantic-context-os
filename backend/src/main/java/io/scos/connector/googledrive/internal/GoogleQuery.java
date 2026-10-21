package io.scos.connector.googledrive.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** TS가 Google 요청 경로를 만드는 인코딩: 경로 조각은 encodeURIComponent, 쿼리는 URLSearchParams(넣은 순서, 공백은 +). */
final class GoogleQuery {
  private GoogleQuery() {}

  /** encodeURIComponent: 영숫자와 -_.!~*'() 밖은 UTF-8 퍼센트 인코딩. */
  static String component(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~");
  }

  /** URLSearchParams.toString(). */
  static String form(Map<String, String> query) {
    return query.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
