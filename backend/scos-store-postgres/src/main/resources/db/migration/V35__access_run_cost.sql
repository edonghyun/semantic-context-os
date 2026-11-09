SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '1s';

-- 기존 run의 NULL은 미측정이다. 호출 수만 독립적으로 미측정일 수 있다.
ALTER TABLE collection_runs
  ADD COLUMN access_target_count BIGINT,
  ADD COLUMN access_call_count BIGINT,
  ADD COLUMN access_elapsed_ms BIGINT,
  ADD COLUMN access_cost_recorded_at TIMESTAMPTZ,
  ADD CONSTRAINT access_target_count_nonnegative CHECK (access_target_count >= 0),
  ADD CONSTRAINT access_call_count_nonnegative CHECK (access_call_count >= 0),
  ADD CONSTRAINT access_elapsed_ms_nonnegative CHECK (access_elapsed_ms >= 0),
  ADD CONSTRAINT access_cost_complete CHECK (
    (access_target_count IS NULL AND access_elapsed_ms IS NULL
      AND access_cost_recorded_at IS NULL AND access_call_count IS NULL)
    OR (access_target_count IS NOT NULL AND access_elapsed_ms IS NOT NULL
      AND access_cost_recorded_at IS NOT NULL)
  );
