package io.scos.client;

import tools.jackson.databind.json.JsonMapper;

/** 클라이언트 저장 JSON의 공통 형식. Jackson 3 매퍼는 불변이며 스레드 간 공유한다. */
public final class ClientJson {
  public static final String MEDIA_TYPE = "application/json";
  public static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ClientJson() {}
}
