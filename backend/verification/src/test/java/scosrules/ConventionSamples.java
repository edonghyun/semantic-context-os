package scosrules;

import io.scos.engine.support.PureFunctions;
import java.util.List;
import java.util.Optional;

/**
 * 코딩 규약 규칙(io.scos.ConventionRulesTest)이 실제로 잡는지 보는 표본. 패키지가 io.scos 밖이라 본 규칙의 대상이 아니다. 이름이 곧 기대다:
 * Violates…는 규칙에 걸려야 하고 Passes…는 걸리지 않아야 한다.
 */
public final class ConventionSamples {
  private ConventionSamples() {}

  public static final class PassesSelfFactory {
    public static PassesSelfFactory of() {
      return new PassesSelfFactory();
    }
  }

  public static final class PassesOptionalFactory {
    public static Optional<PassesOptionalFactory> parse(String text) {
      return text.isEmpty() ? Optional.empty() : Optional.of(new PassesOptionalFactory());
    }
  }

  /** 하위 타입을 돌려주는 정적 생성 메서드도 자기 타입의 팩토리다. */
  public abstract static class PassesSubtypeFactory {
    public static Leaf leaf() {
      return new Leaf();
    }

    public static final class Leaf extends PassesSubtypeFactory {}
  }

  public static final class PassesMain {
    public static void main(String[] args) {}
  }

  /** enum의 암묵 values()·valueOf(String)는 규칙 밖이다. */
  public enum PassesEnum {
    ONE
  }

  /** public이 아닌 정적 메서드는 대상이 아니다. */
  public static final class PassesPackagePrivateStatic {
    static String helper() {
      return "";
    }
  }

  /** 표본 계약(java 밖 인터페이스 — 포트 자리). */
  public interface Contract {}

  /** 조립 팩토리(§2.5): 인스턴스를 만들 수 없는 팩토리 클래스가 계약을 돌려준다. */
  public static final class PassesAssemblyFactory {
    private PassesAssemblyFactory() {}

    public static Contract contract() {
      return new Contract() {};
    }
  }

  /** 순수 계산 모듈(§1.1 예외 2): 선언하고 구조를 지킨다. */
  @PureFunctions
  public static final class PassesPureFunctions {
    private static final String SUFFIX = "!";

    private PassesPureFunctions() {}

    public static String shout(String text) {
      return text + SUFFIX;
    }
  }

  /** 봉인 인터페이스는 포트가 아니라 값의 합 타입이다 — 순수 계산 함수가 인자로 받아도 된다. */
  public sealed interface SealedValue permits SealedValue.One {
    record One() implements SealedValue {}
  }

  @PureFunctions
  public static final class PassesPureTakingSealedValue {
    private PassesPureTakingSealedValue() {}

    public static boolean isOne(SealedValue value) {
      return value instanceof SealedValue.One;
    }
  }

  /** 인스턴스를 만들 수 있는 클래스의 계약 반환은 조립 팩토리가 아니다. */
  public static final class ViolatesAssemblyFromInstantiable {
    public static Contract contract() {
      return new Contract() {};
    }
  }

  /** JDK 인터페이스는 계약이 아니다. */
  public static final class ViolatesJdkInterfaceFromFactoryClass {
    private ViolatesJdkInterfaceFromFactoryClass() {}

    public static Runnable task() {
      return () -> {};
    }
  }

  /** 순수 계산 선언인데 바뀌는 static 상태가 있다(구조 규칙 위반). */
  @PureFunctions
  public static final class ViolatesPureWithState {
    private static int calls;

    private ViolatesPureWithState() {}

    public static int count() {
      return ++calls;
    }
  }

  /** 순수 계산 선언인데 계약(포트)을 인자로 받는다(구조 규칙 위반). */
  @PureFunctions
  public static final class ViolatesPureTakingContract {
    private ViolatesPureTakingContract() {}

    public static String describe(Contract contract) {
      return contract.toString();
    }
  }

  /** 순수 계산 선언인데 인스턴스를 만들 수 있다(구조 규칙 위반). */
  @PureFunctions
  public static class ViolatesPureInstantiable {
    public static String same(String text) {
      return text;
    }
  }

  public static final class ViolatesStaticFunction {
    public static String normalize(String text) {
      return text.strip();
    }
  }

  public static final class ViolatesListOfSelf {
    public static List<ViolatesListOfSelf> all() {
      return List.of();
    }
  }

  public static final class ViolatesOptionalOfOther {
    public static Optional<String> find() {
      return Optional.empty();
    }
  }

  public static final class ViolatesNamingUtils {}

  public static final class ViolatesNamingUtil {}

  public static final class ViolatesNamingHelper {}

  public static final class PassesTextNormalizer {}
}
