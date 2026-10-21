package io.scos.collection.internal.credentials;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * `file:<경로>` — 파일 내용의 앞뒤 공백을 TS `trim()`처럼 뗀 값(TS FileCredentialResolver). 잘못된 UTF-8은 TS(Node)처럼
 * 대체 문자로 읽는다. 파일 권한은 검사하지 않는다(TS와 같음, ADR-0022 §2). TS와 다른 점: 정규 파일만 받고(FIFO·장치 파일은 열다가 영원히 막히거나
 * 끝없이 읽는다) 크기 상한 {@link #MAX_BYTES}를 둔다 — `command:`의 출력 상한과 같은 크기다. 실패 오류에는 경로만 싣는다.
 */
public final class FileCredentials implements CredentialScheme {
  public static final int MAX_BYTES = 1024 * 1024;

  @Override
  public String name() {
    return "file";
  }

  @Override
  public String resolve(String reference) {
    Path path = Path.of(reference);
    if (Files.exists(path) && !Files.isRegularFile(path))
      throw CredentialFailures.invalid("credential file is not a regular file: " + reference);
    byte[] bytes;
    try (InputStream in = Files.newInputStream(path)) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    } catch (IOException | RuntimeException e) {
      throw CredentialFailures.invalid("credential file could not be read: " + reference);
    }
    if (bytes.length > MAX_BYTES)
      throw CredentialFailures.invalid(
          "credential file exceeds " + MAX_BYTES + " bytes: " + reference);
    String value = CredentialText.trim(new String(bytes, StandardCharsets.UTF_8));
    if (value.isEmpty()) throw CredentialFailures.invalid("credential file is empty: " + reference);
    return value;
  }
}
