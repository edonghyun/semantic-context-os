---
updated: 2026-09-24T07:00:00Z
tags: [release]
---
# Release notes 3.4.0 (2026-09-24)

## Customer app

- Order history now loads with cursor pagination from Order API v2 — scrolling long histories is faster.
- Fixed a crash on Android when the saved address list was empty at checkout ([OTC-352](../jira/OTC-352)).

## Payments

- Payment capture retries reuse the original idempotency key ([OTC-311](../jira/OTC-311)). The capture retry flag is back on.
- New alert for duplicate captures on the same order ([OTC-312](../jira/OTC-312)).

## Dispatch

- Rider order batching rolled out to 30% of riders in Seoul ([OTC-322](../jira/OTC-322)).

## Known issues

- The checkout end-to-end test is still flaky on CI ([OTC-327](../jira/OTC-327)).
