package io.scos.connector.notion.internal;

import io.scos.engine.support.text.JsJson;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.RejectedItem;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.node.ObjectNode;

/**
 * run 하나의 Notion 공급자 호출: 실행기({@link CallExecutor} — 재시도·대기·취소)로 부르고 {@link NotionErrors}로 분류한다.
 * 위치(locator)를 주면 object_not_found를 run 실패가 아니라 그 항목의 거부(unsupported_item)로 격리한다 — 그 객체가 통합에 공유되지
 * 않은 것이다(흔히 synced_block의 원본, 공유 범위 밖 database·페이지 링크). 설정한 루트 페이지 자체의 조회만 위치 없이 불러 run을 실패시킨다(루트가
 * 공유되지 않은 것은 설정 오류다). restricted_resource는 격리하지 않는다.
 */
public final class NotionCalls {
  static final String UNSHARED =
      "Notion object is not shared with the integration (commonly the original of a "
          + "synced_block, or a link to a database/page outside the shared scope): ";

  /** 호출 결과: 값이거나, 격리된 거부. */
  sealed interface Outcome<T> {
    <R> R fold(Function<RejectedItem, R> rejected, Function<T, R> value);

    record Value<T>(T value) implements Outcome<T> {
      @Override
      public <R> R fold(Function<RejectedItem, R> rejected, Function<T, R> onValue) {
        return onValue.apply(value);
      }
    }

    record Rejected<T>(RejectedItem rejection) implements Outcome<T> {
      @Override
      public <R> R fold(Function<RejectedItem, R> onRejected, Function<T, R> value) {
        return onRejected.apply(rejection);
      }
    }
  }

  /** 분류 중에 던져 실행기를 그대로 빠져나온다(실행기는 분류가 던진 것을 감싸지 않는다). */
  private static final class Unshared extends RuntimeException {
    private final NotionApiException notFound;

    Unshared(NotionApiException cause) {
      super(cause.getMessage(), null, false, false);
      this.notFound = cause;
    }
  }

  private final CollectionInput input;
  private final Clock clock;

  public NotionCalls(CollectionInput input, Clock clock) {
    this.input = input;
    this.clock = clock;
  }

  /** 실패하면 run을 끝낸다. */
  <T> T required(String operation, CallExecutor.Call<T> call) {
    return input.calls().execute(operation, call, NotionErrors::classify, input.cancel());
  }

  <T> T paged(String operation, Optional<String> cursor, CallExecutor.Call<T> call) {
    return input
        .calls()
        .execute(
            operation,
            call,
            error -> {
              if (cursor.isPresent()
                  && error instanceof NotionApiException api
                  && api.invalidCursor()) throw new NotionCursorExpired();
              return NotionErrors.classify(error);
            },
            input.cancel());
  }

  /** 이 작업의 호출. 실패하면 작업의 위치를 단 실패로 run을 끝낸다. */
  <T> T required(NotionWork work, CallExecutor.Call<T> call) {
    try {
      return required(work.operation(), call);
    } catch (CallFailureException e) {
      throw located(e, work);
    }
  }

  /**
   * 이 작업의 호출. object_not_found면 작업의 위치(+ operation)를 담은 거부로 격리하고, 그 밖의 실패는 작업의 위치를 달아 run을 끝낸다(거부와
   * 같은 위치 — operation은 실패 이벤트의 별도 키라 위치에 넣지 않는다).
   */
  <T> Outcome<T> isolated(NotionWork work, CallExecutor.Call<T> call) {
    return isolated(work, Optional.empty(), call);
  }

  <T> Outcome<T> isolated(NotionWork work, Optional<String> cursor, CallExecutor.Call<T> call) {
    String operation = work.operation();
    ObjectNode locator = work.locator();
    try {
      return new Outcome.Value<>(
          input
              .calls()
              .execute(
                  operation,
                  call,
                  error -> {
                    if (cursor.isPresent()
                        && error instanceof NotionApiException api
                        && api.invalidCursor()) throw new NotionCursorExpired();
                    return classifyIsolating(error);
                  },
                  input.cancel()));
    } catch (CallFailureException e) {
      throw located(e, work);
    } catch (Unshared e) {
      ObjectNode located = locator.deepCopy().put("operation", operation);
      return new Outcome.Rejected<>(
          new RejectedItem(
              RejectedItem.FailureKind.UNSUPPORTED_ITEM,
              UNSHARED + e.notFound.getMessage(),
              Optional.of(JsJson.stringify(located)),
              clock.instant(),
              Optional.empty()));
    }
  }

  private static CallFailureException located(CallFailureException failure, NotionWork work) {
    return failure.at(JsJson.stringify(work.locator()));
  }

  private static CallFailure classifyIsolating(Exception error) {
    if (error instanceof NotionApiException api && api.code().equals(NotionErrors.OBJECT_NOT_FOUND))
      throw new Unshared(api);
    return NotionErrors.classify(error);
  }
}
