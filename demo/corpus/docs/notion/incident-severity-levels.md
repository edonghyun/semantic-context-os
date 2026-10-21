---
updated: 2026-07-30T00:00:00Z
tags: [oncall, incident]
---
# Incident severity levels

## SEV1

Customers cannot order or pay, money is moved incorrectly (double charges, wrong refunds), or personal data is exposed. Page immediately, open an incident channel, assign an incident commander, update every 30 minutes. Postmortem required within 5 business days.

## SEV2

A major feature is degraded for many customers but there is a workaround: delayed push notifications, slow checkout (p95 > 3 s), one region's dispatch failing. Page the primary on-call. Postmortem required.

## SEV3

Minor bugs or internal tooling issues with no customer money or data impact. Create a ticket; handle in business hours. No postmortem needed.

## Upgrading and downgrading

Anyone can upgrade the severity. Only the incident commander downgrades it, and they say why in the channel.
