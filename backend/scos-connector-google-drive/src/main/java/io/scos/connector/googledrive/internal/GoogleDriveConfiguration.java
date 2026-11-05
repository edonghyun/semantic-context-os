package io.scos.connector.googledrive.internal;

import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.SourceConfig;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drive 소스 설정: schemaVersion 1, accountEmail(공백·@가 아닌 글자 + @ + 공백·@가 아닌 글자 — 공백은 JS `\s`),
 * downloadBinaries(선택, 불리언, 기본 true), permissionVisibility(기본 UNVERIFIED). 계정은 소문자로 둔다 — OAuth 계정과
 * 비교하고 원본 metadata에 실린다. 거부 메시지는 고정 문구다.
 */
public record GoogleDriveConfiguration(
    String accountEmail, boolean downloadBinaries, PermissionVisibility permissionVisibility) {
  /** 전체성 근거가 승인된 소스만 VERIFIED_FULL을 명시한다. */
  public enum PermissionVisibility {
    UNVERIFIED,
    VERIFIED_FULL
  }

  private static final Pattern ACCOUNT =
      Pattern.compile("^[^@" + JsValues.JS_WHITESPACE + "]+@[^@" + JsValues.JS_WHITESPACE + "]+$");

  public static GoogleDriveConfiguration read(ObjectMapper json, SourceConfig config) {
    JsonNode value = json.readTree(config.valueJson());
    if (value == null || !value.isObject()) throw invalid("Invalid Google API object");
    JsonNode account = value.get("accountEmail");
    JsonNode download = value.get("downloadBinaries");
    if (config.schemaVersion() != 1
        || account == null
        || !account.isString()
        || !ACCOUNT.matcher(account.asString()).matches()
        || (download != null && !download.isBoolean()))
      throw invalid("Invalid Google Drive source configuration");
    return new GoogleDriveConfiguration(
        account.asString().toLowerCase(Locale.ROOT),
        download == null || download.asBoolean(),
        visibility(value));
  }

  private static PermissionVisibility visibility(JsonNode value) {
    JsonNode visibility = value.get("permissionVisibility");
    if (visibility == null) return PermissionVisibility.UNVERIFIED;
    if (visibility.isString()) {
      if (visibility.asString().equals("UNVERIFIED")) return PermissionVisibility.UNVERIFIED;
      if (visibility.asString().equals("VERIFIED_FULL")) return PermissionVisibility.VERIFIED_FULL;
    }
    throw invalid("Invalid Google Drive source configuration");
  }

  private static CallFailureException invalid(String message) {
    return new CallFailureException(
        "google-drive.configuration",
        CallFailure.of(CallFailure.Kind.INVALID_CONFIGURATION, message));
  }
}
