package io.scos.spi.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 접근 페이지와 비용을 함께 기록하며 페이지 저장 경계의 장애도 주입할 수 있다. */
public final class RecordedAclSink implements AclSink {
  private final List<AclPage> pages = new ArrayList<>();
  private final List<AccessCost> costs = new ArrayList<>();
  private final Consumer<AclPage> store;

  public RecordedAclSink() {
    this(page -> {});
  }

  public RecordedAclSink(Consumer<AclPage> store) {
    this.store = store;
  }

  @Override
  public void accept(AclPage page) {
    store.accept(page);
    pages.add(page);
  }

  @Override
  public void cost(AccessCost cost) {
    costs.add(cost);
  }

  public List<AclPage> pages() {
    return List.copyOf(pages);
  }

  public List<AccessCost> costs() {
    return List.copyOf(costs);
  }
}
