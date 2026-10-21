---
updated: 2026-09-16T00:00:00Z
tags: [guide, feature-flags]
---
# 기능 플래그 가이드

## 이름 규칙

`<서비스>.<기능>` 형식(예: `payments.capture-retry`, `dispatch.batching`). 실험 플래그는 `exp.` 접두사를 붙인다.

## 수명

- 모든 플래그는 만들 때 담당자와 만료일을 적는다. 기본 만료는 90일.
- 100% 켜진 지 30일이 지난 플래그는 오래된 플래그(stale flag)로 보고 코드에서 지운다.
- 매달 첫 주에 플랫폼 팀이 오래된 플래그 목록을 `#eng-general`에 올린다([OTC-355](../jira/OTC-355)).

## 온콜이 끌 수 있는 플래그

`payments.*`, `dispatch.*`, `notify.*`는 장애 시 온콜이 승인 없이 끌 수 있다. 끈 사실은 `#oncall`에 남긴다.
