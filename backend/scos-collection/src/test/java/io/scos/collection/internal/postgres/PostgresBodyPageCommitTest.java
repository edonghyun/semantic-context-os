package io.scos.collection.internal.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.scos.collection.internal.BodyCommitScenario;
import io.scos.collection.internal.BodyCommitScenario.StoredEffects;
import io.scos.collection.internal.BodyPageCommitContract;
import io.scos.collection.internal.CheckpointConflictException;
import io.scos.collection.internal.CommitContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("db")
@DisplayName("PostgreSQL 본문 페이지 커밋 계약")
class PostgresBodyPageCommitTest extends BodyPageCommitContract {
  @Override
  protected BodyCommitScenario newContext() {
    PostgresSupport.truncateAll();
    return new BodyCommitScenario(
        CommitContext.Ports.postgres(PostgresSupport.dataSource()),
        new PostgresBodyCompletionStore(JdbcClient.create(PostgresSupport.dataSource())));
  }

  @Test
  @DisplayName("체크포인트 충돌은 raw와 본문 완료도 롤백한다")
  void checkpointConflictRollsBackBody() {
    // given
    context.checkpointWillConflict();
    // when·then
    assertThatThrownBy(context::completeBody).isInstanceOf(CheckpointConflictException.class);
    assertThat(context.storedEffects()).isEqualTo(new StoredEffects(0, 0, false, false));
  }

  @Test
  @DisplayName("체크포인트 충돌은 부분 삭제와 소속 교체도 롤백한다")
  void checkpointConflictRollsBackScopeCleanup() {
    // given
    context.previousBodyHasRemovedBlock();
    context.checkpointWillConflict();
    // when·then
    assertThatThrownBy(context::completeBody).isInstanceOf(CheckpointConflictException.class);
    assertThat(context.removedBlockIsActive()).isTrue();
    assertThat(context.ownsRemovedBlock()).isTrue();
  }
}
