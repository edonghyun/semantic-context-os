---
updated: 2026-09-15T02:00:00Z
tags: [slack, feature-flags]
---
# #eng-general Stale feature flag cleanup for October

**daniel** Monthly reminder: 14 flags have been at 100% for more than 30 days. Owners, please remove them from code before Oct 8 — list in [OTC-355](../../jira/OTC-355). Rules are in the [feature flag guide](../../notion/feature-flag-guide).

**jiwoo** `checkout.new-summary` is mine, PR is up.

**jaehoon** Please do not remove `payments.capture-retry`. It is the kill switch we used during the duplicate charge incident.

**daniel** Right, kill switches are exempt. I'll tag them as operational in the console.
