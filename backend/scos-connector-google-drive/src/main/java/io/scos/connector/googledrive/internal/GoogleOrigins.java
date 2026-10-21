package io.scos.connector.googledrive.internal;

import java.net.URI;

/**
 * Google API origin(TS의 고정 origins 표와 OAuth 토큰 끝점). 운영은 {@link #GOOGLE}이다 — 자격 증명은 이 origin으로만 나간다.
 * 경로는 늘 클라이언트가 붙이므로 다른 origin으로 새지 않는다.
 */
public record GoogleOrigins(URI drive, URI docs, URI sheets, URI slides, URI oauth) {
  public static final GoogleOrigins GOOGLE =
      new GoogleOrigins(
          URI.create("https://www.googleapis.com"),
          URI.create("https://docs.googleapis.com"),
          URI.create("https://sheets.googleapis.com"),
          URI.create("https://slides.googleapis.com"),
          URI.create("https://oauth2.googleapis.com"));

  URI of(GoogleService service) {
    return switch (service) {
      case DRIVE -> drive;
      case DOCS -> docs;
      case SHEETS -> sheets;
      case SLIDES -> slides;
    };
  }
}
