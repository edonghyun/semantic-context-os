package scosrules;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 생성자에서 직접 또는 도우미를 통해 자원을 여는지 검사하는 바이트코드 표본이다. 실행하지 않는다. */
public final class ClientLifecycleSamples {
  private ClientLifecycleSamples() {}

  public static final class PassesPureConstructor {
    private final int value;

    public PassesPureConstructor() {
      value = normalize(1);
    }

    public int value() {
      return value;
    }

    private static int normalize(int value) {
      return value < 0 ? normalize(-value) : value;
    }

    public ExecutorService open() {
      return Executors.newSingleThreadScheduledExecutor();
    }
  }

  public static final class ViolatesDirectAcquisition {
    public ViolatesDirectAcquisition() {
      Executors.newSingleThreadScheduledExecutor();
    }
  }

  public static final class ViolatesHelperAcquisition {
    public ViolatesHelperAcquisition() {
      acquire();
    }

    private static ExecutorService acquire() {
      return Resources.open();
    }
  }

  public static final class Resources {
    private Resources() {}

    static ExecutorService open() {
      return Executors.newSingleThreadScheduledExecutor();
    }
  }
}
