---
updated: 2026-09-09T11:30:00Z
tags: [slack, incident, payments, refund]
---
# #inc-payments 중복 승인 환불 진행 상황

**jaehoon** 09-08 10:05 Refund script dry-run done: 412 orders, 11,820,400 KRW. Finance confirmed the list.

**jaehoon** 09-08 15:10 1차 실행 결과: 380건 환불 성공, 32건은 카드사 응답 지연으로 보류. 보류 건은 내일 오전에 다시 돌립니다.

**sora** 09-08 15:30 Duplicate capture alert draft is up for review ([OTC-312](../../jira/OTC-312)). Backtest catches all 412 within 2 minutes.

**jaehoon** 09-09 11:05 보류 32건 재실행 전부 성공. 환불 412건 완료됐고 고객 안내 문자 나갔습니다.

**minji** 09-09 11:30 수고하셨습니다. SEV1 종료합니다. 포스트모템 초안: [포스트모템](../../notion/postmortem-2026-09-07-duplicate-charges)
