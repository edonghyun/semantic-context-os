package io.scos.connector.tiro.internal;

import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CancellationToken;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Tiro GET 한 번: 호출 실행기(재시도·대기·취소)를 거쳐 읽고 실패를 분류한다. 본문 수집과 접근 수집이 같은 경로를 쓴다. 재시도를 포함한 실제 시도 수를
 * 센다({@link #attempts()}).
 */
final class TiroReads {
  /** 읽는 경로의 범위: 노트 아래 경로의 403·404만 "그 노트(또는 그 종류)를 읽을 수 없다"로 본다. */
  enum Scope {
    WORKSPACE,
    NOTE
  }

  /** 노트 경로의 403·404 — 분류 대신 호출자에게 알린다(재시도·run 실패 아님). */
  static final class UnavailableNote extends RuntimeException {
    UnavailableNote() {
      super("Tiro note resource unavailable", null, false, false);
    }
  }

  private final CallExecutor calls;
  private final CancellationToken cancel;
  private final TiroApiClient client;
  private final String token;
  private long attempts;

  TiroReads(CallExecutor calls, CancellationToken cancel, TiroApiClient client, String token) {
    this.calls = calls;
    this.cancel = cancel;
    this.client = client;
    this.token = token;
  }

  /** 이 경로를 읽는다. 노트 범위의 403·404는 {@link UnavailableNote}, 그 밖의 실패는 {@link TiroErrors} 분류다. */
  JsonNode read(String path, Map<String, String> query, Scope scope) {
    return calls.execute(
        "tiro.read",
        timeout -> {
          attempts++;
          return client.get(token, path, query, timeout);
        },
        error -> {
          if (scope == Scope.NOTE
              && error instanceof TiroApiException api
              && (api.status() == TiroErrors.FORBIDDEN || api.status() == TiroErrors.NOT_FOUND))
            throw new UnavailableNote();
          return TiroErrors.classify(error);
        },
        cancel);
  }

  /** 지금까지 보낸 GET 시도 수(재시도 포함). */
  long attempts() {
    return attempts;
  }
}
