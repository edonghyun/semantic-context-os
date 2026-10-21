package io.scos.connector.googledrive.internal;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google OAuth 자격 증명(TS session의 credential JSON: `client_id`·`client_secret`·`refresh_token`).
 * credentialRef(보통 `file:`)가 가리키는 JSON을 읽는다. 오류 메시지는 TS와 같고 값을 싣지 않는다. {@link #toString()}은 비밀값을
 * 가린다.
 */
record GoogleCredential(String clientId, String clientSecret, String refreshToken) {
  private static final List<String> KEYS = List.of("client_id", "client_secret", "refresh_token");

  static GoogleCredential parse(ObjectMapper json, String serialized) {
    JsonNode node;
    try {
      node = json.readTree(serialized);
    } catch (JacksonException e) {
      throw new GoogleProtocolException("Invalid Google OAuth credential JSON");
    }
    if (!node.isObject()) throw new GoogleProtocolException("Invalid Google OAuth credential JSON");
    for (String key : KEYS)
      if (!JsValues.nonEmptyString(node.get(key)))
        throw new GoogleProtocolException("Google OAuth credential requires " + key);
    return new GoogleCredential(
        node.get("client_id").asString(),
        node.get("client_secret").asString(),
        node.get("refresh_token").asString());
  }

  @Override
  public String toString() {
    return "GoogleCredential[clientId=" + clientId + ", clientSecret=***, refreshToken=***]";
  }
}
