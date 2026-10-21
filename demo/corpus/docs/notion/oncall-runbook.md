---
updated: 2026-09-12T00:00:00Z
tags: [oncall, runbook]
---
# 온콜 런북 (On-call runbook)

## 페이지를 받으면

1. 5분 안에 페이지를 ack한다. ack하지 않으면 10분 뒤 세컨더리로 넘어간다.
2. `#oncall`에 "보고 있음" 한 줄을 남긴다.
3. 심각도를 정한다 — 기준은 [incident severity levels](incident-severity-levels).

## SEV1 에스컬레이션

SEV1이면 즉시 인시던트 채널(`#inc-<서비스>`)을 열고 인시던트 커맨더(IC)를 지정한다. 15분 안에 원인을 못 찾으면 해당 서비스 팀 리드에게 전화한다. 결제 관련 SEV1은 CS 리드와 재무팀에도 알린다. 30분마다 상태 업데이트를 올린다.

## 자주 쓰는 조치

- 기능 플래그 끄기: 플래그 콘솔에서 `payments.*`, `dispatch.*` 플래그는 온콜이 직접 끌 수 있다.
- 롤백: `deploy rollback <service> --to <version>`. 롤백 전에 DB 마이그레이션 여부를 확인한다.
- 큐 적체: notify 큐 깊이가 2만을 넘으면 컨슈머를 늘린다(`scale notify-consumer 8`).

## 인수인계

교대 시 `#oncall`에 진행 중 이슈, 끈 플래그, 다음 담당자에게 넘길 것을 적는다.
