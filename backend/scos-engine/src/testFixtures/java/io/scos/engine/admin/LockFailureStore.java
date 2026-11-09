package io.scos.engine.admin;

import io.scos.spi.access.AdminStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 실제 저장소의 트랜잭션에서 다음 관리 잠금 획득만 실패시킨다. */
public final class LockFailureStore implements AdminStore {
  private final AdminStore delegate;
  private final AtomicBoolean failNext = new AtomicBoolean();
  private final AtomicBoolean failSource = new AtomicBoolean();

  public LockFailureStore(AdminStore delegate) {
    this.delegate = delegate;
  }

  public void failNextAdministrationLock() {
    failNext.set(true);
  }

  public void failNextSourceLock() {
    failSource.set(true);
  }

  @Override
  public <T> T transact(Function<Session, T> work) {
    return delegate.transact(
        session ->
            work.apply(
                (Session)
                    Proxy.newProxyInstance(
                        Session.class.getClassLoader(),
                        new Class<?>[] {Session.class},
                        (proxy, method, arguments) -> invoke(session, method, arguments))));
  }

  private Object invoke(Session session, Method method, Object[] arguments) throws Throwable {
    if (method.getName().equals("lockAdministration") && failNext.getAndSet(false))
      throw new IllegalStateException("injected administration lock failure");
    if (method.getName().equals("lockSource") && failSource.getAndSet(false))
      throw new IllegalStateException("injected source lock failure");
    try {
      return method.invoke(session, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
