package io.scos.engine.support.call;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 체크포인트에 남는 수집 작업 대기열(불변). 앞에서 작업을 꺼내 처리하고, 끝나면 그 자리를 "앞에 둘 작업(이어 읽기·자식) → 남은 작업 → 뒤에 둘 작업(발견한 작업)"
 * 순서로 바꾼다. 저장 형식(작업 JSON 배열)은 커넥터가 정한다 — 이 순서가 곧 체크포인트의 작업 순서다.
 */
public final class WorkQueue<W> {
  private final List<W> items;

  private WorkQueue(List<W> items) {
    this.items = List.copyOf(items);
  }

  public static <W> WorkQueue<W> of(List<W> items) {
    return new WorkQueue<>(items);
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  public W head() {
    return items.getFirst();
  }

  /** 대기 순서 그대로(체크포인트에 쓰는 순서). */
  public List<W> items() {
    return items;
  }

  /** 앞의 done개를 끝냈다: front, 남은 작업, back 순서의 새 대기열. */
  public WorkQueue<W> afterHead(int done, List<W> front, List<W> back) {
    List<W> next = new ArrayList<>(front);
    next.addAll(items.subList(done, items.size()));
    next.addAll(back);
    return new WorkQueue<>(next);
  }

  /** 첫 작업(지금 하는 것)은 두고 조건에 맞는 작업을 버린 새 대기열. */
  public WorkQueue<W> withoutQueued(Predicate<W> drop) {
    List<W> kept = new ArrayList<>();
    for (int i = 0; i < items.size(); i++)
      if (i == 0 || !drop.test(items.get(i))) kept.add(items.get(i));
    return new WorkQueue<>(kept);
  }
}
