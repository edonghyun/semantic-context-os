---
updated: 2026-09-01T00:00:00Z
tags: [onboarding, backend]
---
# 백엔드 신규 입사자 온보딩 가이드

## 첫 주 체크리스트

- 사내 계정, GitHub 조직, 클라우드 콘솔 읽기 권한 요청(IT 헬프데스크 폼)
- `#eng-general`, `#help-dev`, `#release`, `#oncall` 채널 참여
- 버디와 30분 1:1 — 서비스 지도(order-api, payment-gateway, dispatch, inventory-sync, notify) 설명 듣기

## 로컬 개발 환경

1. JDK 21과 Docker Desktop을 설치한다.
2. `ottercart/platform` 저장소를 받고 `make dev-up`으로 PostgreSQL·Redis·가짜 Bramblepay를 띄운다.
3. Apple silicon(M 시리즈) 맥에서는 `DOCKER_DEFAULT_PLATFORM=linux/arm64`를 설정한다. amd64 이미지로 빌드하면 느리고 일부 테스트가 실패한다.
4. `./gradlew test`가 통과하면 준비 끝.

## 코드 리뷰와 배포

- PR은 최소 1명 승인, 결제 경로(payment-gateway, checkout)는 checkout 팀 승인이 필수다.
- 배포는 매주 화·목 오후 2시 릴리스 트레인. 핫픽스는 온콜 승인 후 언제든 가능.

## 온콜

입사 6주 차부터 온콜 섀도잉을 시작한다. 절차는 [온콜 런북](oncall-runbook)을 본다.
