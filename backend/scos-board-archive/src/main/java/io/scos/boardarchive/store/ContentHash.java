package io.scos.boardarchive.store;

import io.scos.boardarchive.driver.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/** JSON arrays frame fields unambiguously, so separators in text cannot collide. */
final class ContentHash {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ContentHash() {}

  static String post(FetchResult.Found f) {
    return hash(
        JSON.writeValueAsString(
            new Object[] {f.title(), f.text(), f.createdAt().toString(), f.attachments()}));
  }

  static String comment(CommentData c) {
    return hash(
        JSON.writeValueAsString(new Object[] {c.text(), c.author(), c.createdAt().toString()}));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
