---
updated: 2026-08-20T06:00:00Z
tags: [slack, platform, database]
---
# #eng-general PostgreSQL 17 업그레이드 공지

**daniel** 주문 DB와 재고 DB를 PostgreSQL 17로 올립니다. 점검 시간은 재고 DB 9월 29일(화) 03:00~04:00, 주문 DB 10월 6일(화) 03:00~04:00입니다. 점검 동안 주문 접수가 막힙니다. 자세한 건 [업그레이드 계획](../../notion/postgres-17-upgrade-plan).

**priya** 확장 기능 버전도 같이 바뀌나요? pg_trgm 쓰는 쿼리가 있어서요.

**daniel** pg_trgm은 그대로 지원됩니다. 스테이징은 이미 17이라 거기서 쿼리 플랜 한 번 확인 부탁드려요.

**hyejin** 재고 DB 점검 때 inventory-sync 배치는 멈춰 둘게요.
