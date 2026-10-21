---
updated: 2026-10-05T08:00:00Z
tags: [postmortem, notify, sev2]
---
# Postmortem: 2026-10-02 push notification delay (SEV2)

## Summary

From 18:05 to 19:40 KST on 2026-10-02, order status push notifications ("rider picked up", "arriving in 5 min") were delayed by up to 47 minutes on iOS. Android was not affected. About 21,000 notifications were late; none were lost.

## Root cause

The notify service refreshes its APNs auth token every 50 minutes. The refresh call ran **inside the queue consumer thread**. When the APNs token endpoint started timing out, every consumer blocked on the refresh for 30 seconds, and the `notify.ios` queue backed up to 180k messages. See [OTC-340](../jira/OTC-340).

## Detection

We had no alert on queue depth. Customers reported late pushes in app reviews and CS tickets; the on-call engineer noticed at 18:52 from the dashboard.

## Fix

- Moved token refresh to a background scheduler with the old token kept until the new one is ready.
- Added an alert when `notify.ios` depth > 20k for 5 minutes ([OTC-341](../jira/OTC-341)).
- Consumers now use a 3 second timeout for any APNs call.

## Lessons

Never do credential refresh on the hot path. The same pattern exists in the SMS sender; it is tracked in the same ticket.
