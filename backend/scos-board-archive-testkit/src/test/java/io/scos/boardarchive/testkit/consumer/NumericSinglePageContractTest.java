package io.scos.boardarchive.testkit.consumer;

import io.scos.boardarchive.driver.BoardDriver;
import io.scos.boardarchive.testkit.*;
import org.junit.jupiter.api.DisplayName;

@DisplayName("숫자 id 단일 쪽 드라이버는 기본 계약을 지킨다 (BoardDriver)")
class NumericSinglePageContractTest extends NumericBoardDriverContract
    implements CategorizedBoardsContract, StaticBoardsContract {
  @Override
  protected BoardDriver createDriver(LocalSite site, MutableClock clock) {
    return NumericProfileFixture.create(site, clock);
  }
}
