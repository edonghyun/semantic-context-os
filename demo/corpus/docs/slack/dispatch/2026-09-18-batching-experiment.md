---
updated: 2026-09-18T04:00:00Z
tags: [slack, dispatch, experiment]
---
# #dispatch 라이더 묶음 배송 실험 결과 논의

**kevin** Batching experiment results are in the [experiment page](../../notion/rider-batching-experiment): cost per delivery -11.8%, median delivery time +2.1 min.

**seoyeon (PM)** 비용 절감은 좋은데 배송 시간 2분 증가는 고객이 체감할 것 같아요. 지각 배송률도 4.4%로 올랐고요.

**kevin** 반경을 800m에서 600m로 줄이면 시뮬레이션상 중앙값 증가가 1.5분 정도입니다. 그렇게 30% 롤아웃하면 어떨까요?

**seoyeon (PM)** 좋아요. 지각 배송률 4.5% 넘으면 바로 줄이는 조건으로 갑시다. [OTC-322](../../jira/OTC-322)
