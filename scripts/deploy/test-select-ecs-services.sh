#!/usr/bin/env bash
# 임시 Git 저장소로 배포 대상 선별을 검증한다. 빌드 및 AWS 호출은 없다.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
selector="$script_dir/select-ecs-services.sh"
temp_root="$(cd -- "${TMPDIR:-/tmp}" && pwd -P)"
fixture="$(mktemp -d "$temp_root/re-solve-ecs-cd.XXXXXX")"
fixture="$(cd -- "$fixture" && pwd -P)"
if [[ "$fixture" != "$temp_root"/re-solve-ecs-cd.* ]]; then
  printf 'Unexpected fixture path: %s\n' "$fixture" >&2
  exit 1
fi
trap 'cd -- "$temp_root" && rm -rf -- "$fixture"' EXIT

cd -- "$fixture"
git init --quiet

# 외부 Git 설정의 훅·서명에 의존하지 않는 테스트 커밋을 만든다.
commit_fixture() {
  git add --all
  git -c user.name='CD test' -c user.email='cd-test@example.invalid' \
    -c commit.gpgsign=false -c core.hooksPath="$fixture/no-hooks" commit --quiet -m "$1"
  git rev-parse HEAD
}

# 실제 배포 워크플로가 쓰는 스크립트의 출력과 예상 서비스 순서를 비교한다.
assert_services() {
  local label=$1 expected=$2 actual
  shift 2
  actual="$(bash "$selector" "$@")"
  if [[ "$actual" != "$expected" ]]; then
    printf '%s: expected [%s], got [%s]\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
  printf 'PASS: %s\n' "$label"
}

all_services='api-gateway member-service problem-service judge-service contest-service study-service notification-service integration-service ai-service analytics-service'
mkdir -p frontend study-service analytics-service common-event
printf 'initial\n' > study-service/source.txt
base="$(commit_fixture 'base')"

printf 'frontend\n' > frontend/source.txt
frontend="$(commit_fixture 'frontend only')"
assert_services 'frontend-only push' '' push "$base" "$frontend"

printf 'changed\n' >> study-service/source.txt
study="$(commit_fixture 'study change')"
assert_services 'single-service push' 'study-service' push "$frontend" "$study"

git mv study-service/source.txt analytics-service/source.txt
analytics="$(commit_fixture 'move across services')"
assert_services 'cross-service rename' 'study-service analytics-service' push "$study" "$analytics"

printf 'event\n' > common-event/source.txt
event="$(commit_fixture 'event module change')"
assert_services 'common-event consumers' \
  'member-service problem-service judge-service contest-service study-service ai-service' push "$analytics" "$event"
assert_services 'multi-commit push' \
  'member-service problem-service judge-service contest-service study-service ai-service analytics-service' push "$frontend" "$event"

printf 'build configuration\n' > gradle.properties
build="$(commit_fixture 'root build configuration')"
assert_services 'root build configuration' "$all_services" push "$event" "$build"
assert_services 'missing push base' "$all_services" push 0000000000000000000000000000000000000000 "$build"
assert_services 'manual single-service deployment' 'analytics-service' manual analytics-service
assert_services 'manual all-services deployment' "$all_services" manual all
