---
updated: 2026-10-02T19:50:00Z
tags: [slack, incident, notify]
---
# #inc-notify iOS push notifications delayed

**kevin** 18:52 Customers say "arriving in 5 min" pushes come after the rider is already at the door. notify.ios queue depth is 160k and climbing. Declaring SEV2.

**kevin** 19:01 Consumers are all stuck in `ApnsTokenProvider.refresh()`. The APNs token endpoint is timing out at 30 s and the refresh runs on the consumer thread.

**priya** 19:06 Can we pin the old token for now? It is valid for another ~10 minutes.

**kevin** 19:12 Deployed a hotfix that skips the refresh if the current token has > 5 min left, and scaled consumers to 12. Queue draining at ~3k/s.

**kevin** 19:40 Queue back under 1k. Android was never affected. Follow-ups: move refresh off the hot path ([OTC-340](../../jira/OTC-340)) and add a queue depth alert ([OTC-341](../../jira/OTC-341)). Postmortem on Monday.
