---
updated: 2026-09-30T09:00:00Z
tags: [slack, security, payments]
---
# #security Bramblepay API 키 교체 공지

**sora** 정책상 90일 주기라 오늘 09:00에 Bramblepay API 키를 교체합니다. 24시간 동안 신·구 키를 함께 허용하고 내일 09:00에 구 키를 폐기합니다. 진행은 [OTC-333](../../jira/OTC-333).

**jaehoon** payment-gateway는 시크릿 저장소에서 5분 안에 새 키를 읽어 갑니다. 재배포 필요 없어요.

**sora** 09:12 새 키로 승인 정상 확인했습니다.
