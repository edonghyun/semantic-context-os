---
updated: 2026-09-01T08:00:00Z
tags: [slack, api, design-review]
---
# #api Order API v2 design review

**priya** Design doc for Order API v2 is ready for review: [Order API v2 design](../../notion/order-api-v2-design). Main changes: mandatory Idempotency-Key on POST, cursor pagination, one error shape.

**jaehoon** For idempotency, please store the response body with the key, not just the status. Partners retry and expect the same order id back.

**priya** Yes, we store the full response for 24 hours. Same key with a different body returns 409 IDEMPOTENCY_KEY_REUSED.

**sora** What about partners still on offset pagination? A few POS vendors depend on `page`.

**priya** v1 keeps working until 2026-12-01; we send Deprecation and Sunset headers before that. Tracking in [OTC-295](../../jira/OTC-295).
