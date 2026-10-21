---
updated: 2026-08-29T05:00:00Z
tags: [design, api, orders]
---
# Order API v2 design

## Goals

Order API v1 uses offset pagination and has no idempotency support. Partners (store POS integrations) retry aggressively and create duplicate orders. v2 fixes both and standardises errors.

## Idempotency

Every `POST /v2/orders` and `POST /v2/orders/{id}/cancel` request must send an `Idempotency-Key` header (UUID v4). The server stores the key with the response for 24 hours. A repeated key with the same body returns the stored response; a repeated key with a different body returns `409 IDEMPOTENCY_KEY_REUSED`. Clients must reuse the same key when they retry.

## Pagination

List endpoints use cursor pagination: `GET /v2/orders?limit=50&cursor=<opaque>`. The response has `nextCursor`, which is null on the last page. `limit` is 1-100, default 20. Offset pagination (`page`, `offset`) is not supported in v2. v1 offset pagination will be removed on 2026-12-01 ([OTC-295](../jira/OTC-295)).

## Errors

Errors use one shape: `{"error": {"code": "ORDER_NOT_FOUND", "message": "...", "requestId": "..."}}`. Codes are stable strings; messages are for humans and can change.

## Rate limits

300 requests per minute per partner key. Over the limit the API returns `429` with a `Retry-After` header in seconds.

## Rollout

Implemented in [OTC-290](../jira/OTC-290). v1 and v2 run side by side until the v1 removal date.
