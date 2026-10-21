---
updated: 2026-09-20T00:00:00Z
tags: [architecture, payments]
---
# payment-gateway architecture

## Responsibilities

payment-gateway is the only service that talks to Bramblepay, our card payment provider. It authorises at checkout, captures when the store accepts the order, and refunds on cancellation.

## Flow

1. checkout calls `POST /payments/authorize` with the order id and amount.
2. When the store accepts, order-api calls `POST /payments/{id}/capture`.
3. payment-gateway sends the capture to Bramblepay with an `Idempotency-Key` derived from the payment id and attempt group, so retries of the same capture reuse the key.

## Retries

Capture calls time out after 5 seconds and are retried once, behind the flag `payments.capture-retry`. Since the September duplicate charge incident the retry must reuse the original idempotency key; a unit test enforces it.

## Monitoring

- `payments.capture.duplicate` alert: two successful captures for one order within 10 minutes.
- Bramblepay latency dashboard (p50/p99 per endpoint).
