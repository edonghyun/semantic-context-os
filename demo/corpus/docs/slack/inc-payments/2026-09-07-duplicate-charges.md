---
updated: 2026-09-07T03:20:00Z
tags: [slack, incident, payments]
---
# #inc-payments 결제 중복 승인 장애 대응 스레드

**minji** 02:41 페이지 받았습니다. CS에서 "두 번 결제됐다" 문의가 10건 넘게 들어왔다고 합니다. SEV2로 시작할게요.

**jaehoon** 02:45 Bramblepay 승인 API가 02:14부터 p99 9초입니다. 우리 쪽 capture 타임아웃이 5초라 재시도가 많이 나가고 있어요.

**jaehoon** 02:50 재시도 요청 로그를 보니 Idempotency-Key가 첫 요청과 다릅니다. 어젯밤 hotfix1에서 재시도 경로가 키를 새로 만들게 바뀐 것 같아요. 그래서 Bramblepay가 두 번 승인합니다.

**minji** 02:52 중복 승인 확인, SEV1로 올립니다. IC는 제가 할게요. `payments.capture-retry` 플래그 끕니다.

**daniel** 02:58 CS 리드랑 재무팀에 공유했습니다. 영향 범위 쿼리 돌리는 중.

**minji** 03:05 플래그 끈 뒤로 신규 중복 승인 0건. 영향 주문은 잠정 412건입니다. 환불은 내일 아침 스크립트로 처리하고([OTC-318](../../jira/OTC-318)), 근본 수정은 [OTC-311](../../jira/OTC-311)로 잡았습니다.

**minji** 03:20 모니터링 계속하고 SEV1 유지합니다. 포스트모템은 수요일까지.
