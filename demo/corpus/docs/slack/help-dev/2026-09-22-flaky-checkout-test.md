---
updated: 2026-09-22T09:00:00Z
tags: [slack, help, testing]
---
# #help-dev Checkout e2e test keeps failing on CI

**jiwoo** `CheckoutFlowE2ETest.paysWithSavedCard` failed on my PR again, timeout waiting for the confirmation screen. Passes locally every time. Anyone seen this?

**sora** Yes, it is flaky for everyone, roughly 1 in 12 runs. Retrying usually works.

**jiwoo** I looked at the CI logs: the fake Bramblepay container sometimes starts after the app, so the first authorise call hits a closed port and the client backs off 10 seconds. I'll add a readiness check instead of the `sleep 5`. Tracking in [OTC-327](../../jira/OTC-327).
