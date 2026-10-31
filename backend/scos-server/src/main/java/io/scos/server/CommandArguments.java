package io.scos.server;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.boot.ApplicationArguments;

/**
 * 명령 인자 읽기: 위치 인자, 필수·선택 옵션, 범위가 있는 정수 옵션. 형식이 틀리면 그 옵션 이름을 밝힌 {@link IllegalArgumentException}
 * (사용법을 붙이는 실패는 {@link Commands#USAGE}).
 */
final class CommandArguments {
  private final ApplicationArguments args;

  CommandArguments(ApplicationArguments args) {
    this.args = args;
  }

  /** index번째 위치 인자. 없으면 사용법과 함께 실패한다. */
  String positional(int index) {
    List<String> positional = args.getNonOptionArgs();
    if (positional.size() <= index) throw new IllegalArgumentException(Commands.USAGE);
    return positional.get(index);
  }

  /** 첫 위치 인자(명령 이름). 없으면 빈 문자열. */
  String command() {
    return args.getNonOptionArgs().isEmpty() ? "" : args.getNonOptionArgs().get(0);
  }

  boolean has(String name) {
    return args.containsOption(name);
  }

  /** 선택 옵션의 값. 옵션 없이는 empty, 값 없이(`--name`) 주면 사용법과 함께 실패한다. */
  Optional<String> option(String name) {
    if (!args.containsOption(name)) return Optional.empty();
    List<String> values = Objects.requireNonNull(args.getOptionValues(name));
    if (values.isEmpty() || values.getFirst().isEmpty())
      throw new IllegalArgumentException("--" + name + " needs a value. " + Commands.USAGE);
    return Optional.of(values.getFirst());
  }

  String required(String name) {
    List<String> values =
        args.containsOption(name) ? Objects.requireNonNull(args.getOptionValues(name)) : List.of();
    if (values.isEmpty())
      throw new IllegalArgumentException("--" + name + " 가 필요합니다. " + Commands.USAGE);
    return values.get(0);
  }

  /** 선택 옵션 하나(양의 정수 초). 없으면 empty. */
  Optional<Duration> positiveSeconds(String name) {
    return option(name)
        .map(raw -> Duration.ofSeconds(within(name, raw, 1, Long.MAX_VALUE, "a positive integer")));
  }

  /** 선택 옵션 하나(int 범위의 양의 정수). 없으면 empty. */
  Optional<Integer> positiveInteger(String name) {
    return option(name)
        .map(raw -> (int) within(name, raw, 1, Integer.MAX_VALUE, "a positive integer"));
  }

  /** 선택 옵션 하나(int 범위의 음이 아닌 정수). 없으면 empty. */
  Optional<Integer> nonNegativeInteger(String name) {
    return option(name)
        .map(raw -> (int) within(name, raw, 0, Integer.MAX_VALUE, "a non-negative integer"));
  }

  /** 선택 옵션 하나(min 이상 max 이하 정수). 없으면 empty. */
  Optional<Integer> intBetween(String name, int min, int max) {
    return option(name)
        .map(
            raw ->
                (int)
                    within(
                        name,
                        raw,
                        min,
                        max,
                        max == Integer.MAX_VALUE
                            ? "a positive integer"
                            : "an integer between " + min + " and " + max));
  }

  /** 선택 옵션 하나(선택지의 와이어 값 중 하나). 아니면 선택지를 밝혀 거부한다. */
  <E extends Enum<E>> Optional<E> choice(String name, E[] choices, Function<E, String> wire) {
    return option(name)
        .map(
            raw -> {
              for (E choice : choices) if (wire.apply(choice).equals(raw)) return choice;
              throw new IllegalArgumentException(
                  "argument '"
                      + raw
                      + "' is invalid. Allowed choices are "
                      + String.join(", ", Arrays.stream(choices).map(wire).toList())
                      + ".");
            });
  }

  /** 10진 정수로 읽어 [min, max] 안이면 그 값. 읽지 못하거나 밖이면 "--name must be {expected}: raw". */
  private static long within(String name, String raw, long min, long max, String expected) {
    long parsed;
    try {
      parsed = Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + name + " must be " + expected + ": " + raw);
    }
    if (parsed < min || parsed > max)
      throw new IllegalArgumentException("--" + name + " must be " + expected + ": " + raw);
    return parsed;
  }
}
