package io.scos.connector.googledrive.internal;

/** Google API 서비스(TS GoogleService). 서비스마다 고정 origin이 있고, 경로는 그 서비스의 버전 접두로 시작해야 한다. */
public enum GoogleService {
  DRIVE("/drive/v3/"),
  DOCS("/v1/"),
  SHEETS("/v4/"),
  SLIDES("/v1/");

  private final String pathPrefix;

  GoogleService(String pathPrefix) {
    this.pathPrefix = pathPrefix;
  }

  /** 이 서비스의 경로가 시작해야 하는 접두. */
  String pathPrefix() {
    return pathPrefix;
  }
}
