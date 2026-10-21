---
updated: 2026-08-26T08:00:00Z
tags: [slack, help, docker]
---
# #help-dev M1 맥에서 docker 빌드가 실패해요

**yuna** 새 맥북(M3)에서 `make dev-up` 하면 fake-bramblepay 이미지 빌드가 `exec format error`로 실패합니다. 다른 분들은 어떻게 하셨나요?

**priya** Apple silicon이면 `DOCKER_DEFAULT_PLATFORM=linux/arm64` 설정하세요. 예전 문서엔 amd64로 돼 있는데 그러면 에뮬레이션이라 느리고 테스트 몇 개가 타임아웃 나요.

**yuna** 설정하니 빌드는 되는데 Redis 컨테이너가 안 뜹니다.

**priya** Redis 이미지는 `redis:7` 공식 이미지로 바꿔야 해요. 예전 사내 이미지는 amd64만 있었어요. 온보딩 문서에 반영해 둘게요.

**yuna** 해결됐습니다, 감사합니다!
