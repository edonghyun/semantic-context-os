#!/usr/bin/env bash
# 서버 릴리스 묶음을 만든다: scos-server-<버전>.tar.gz(+ .sha256).
#
#   tools/release/build-bundle.sh <버전> [출력 디렉터리, 기본 dist]
#
# 묶음 = scos-backend.jar(실행 JAR) + bin/(deploy/java/bin의 운영 래퍼) + VERSION(버전·커밋).
# 인스턴스는 이 묶음을 내려받아 sha256을 확인하고 releases/<버전>/에 푼다(docs/operations/java-deploy.md).
# 게이트(check·dbTest·PIT)는 이 스크립트가 아니라 태그를 찍기 전에 통과시킨다. 여기서는 bootJar만 만든다.
# 작업 트리가 깨끗해야 한다 — 묶음의 커밋과 내용이 같아야 하기 때문이다.
set -euo pipefail

ver="${1:?버전이 필요하다 (예: 0.2.0)}"
out="${2:-dist}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

[ -z "$(git status --porcelain --untracked-files=no)" ] || { echo "작업 트리가 깨끗하지 않다" >&2; exit 1; }
commit="$(git rev-parse HEAD)"

(cd backend && ./gradlew --no-daemon -q :scos-server:bootJar)

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/bin"
cp backend/build/libs/scos-backend.jar "$stage/"
for f in deploy/java/bin/scos-*.sh; do
  case "$(basename "$f")" in scos-rollback.sh) continue ;; esac   # 옛 TS 배치 전용 — 묶음에 넣지 않는다
  cp "$f" "$stage/bin/"
done
chmod 755 "$stage"/bin/*.sh
printf 'version=%s\ncommit=%s\n' "$ver" "$commit" > "$stage/VERSION"

mkdir -p "$out"
name="scos-server-$ver.tar.gz"
# 같은 커밋이면 같은 바이트가 나오게: 이름순·고정 시각·소유자 0 (GNU tar). BSD tar에는 이 옵션이 없어 그대로 묶는다.
if tar --version 2>/dev/null | grep -q 'GNU tar'; then
  tar --sort=name --mtime="@$(git log -1 --format=%ct)" --owner=0 --group=0 --numeric-owner -C "$stage" -czf "$out/$name" .
else
  tar -C "$stage" -czf "$out/$name" .
fi
(cd "$out" && { sha256sum "$name" 2>/dev/null || shasum -a 256 "$name"; } > "$name.sha256")
echo "$out/$name"
cat "$out/$name.sha256"
