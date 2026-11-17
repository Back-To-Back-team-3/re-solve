#!/usr/bin/env bash
# 자동 push의 변경 경로 또는 수동 입력을 ECS 배포 대상 목록으로 변환한다.
set -euo pipefail

ALL_SERVICES=(
  api-gateway member-service problem-service judge-service contest-service
  study-service notification-service integration-service ai-service analytics-service
)

# 각 서비스의 build.gradle 기준이다. 간접 런타임 의존성도 포함한다.
WEB_JPA_SERVICES=(
  member-service problem-service judge-service contest-service study-service
  notification-service integration-service
)
EVENT_SERVICES=(
  member-service problem-service judge-service contest-service study-service ai-service
)
SCHEDULING_SERVICES=(member-service problem-service judge-service contest-service)

# NUL로 구분된 경로에서 영향을 받는 서비스를 중복 없이 배포 순서대로 반환한다.
select_changed_services() {
  local path service
  local -a affected targets=()
  local -A selected=()

  while IFS= read -r -d '' path; do
    case "$path" in
      Dockerfile|Dockerfile.dockerignore|build.gradle|settings.gradle|gradle.properties|gradlew|gradle/*)
        affected=("${ALL_SERVICES[@]}") ;;
      common-core/*)
        affected=("${WEB_JPA_SERVICES[@]}" ai-service) ;;
      common-web/*|common-jpa/*)
        affected=("${WEB_JPA_SERVICES[@]}") ;;
      common-event/*)
        affected=("${EVENT_SERVICES[@]}") ;;
      common-scheduling/*)
        affected=("${SCHEDULING_SERVICES[@]}") ;;
      *)
        affected=("${path%%/*}") ;;
    esac
    for service in "${affected[@]}"; do
      selected["$service"]=1
    done
  done

  for service in "${ALL_SERVICES[@]}"; do
    if [[ ${selected[$service]:-} ]]; then
      targets+=("$service")
    fi
  done
  printf '%s\n' "${targets[*]}"
}

# push 전체의 이전·이후 커밋을 비교한다. 비교 기준이 없으면 배포 누락을 피한다.
select_push_services() {
  local before=$1 after=$2
  git cat-file -e "$after^{commit}"
  if ! git cat-file -e "$before^{commit}" 2>/dev/null; then
    printf '%s\n' "${ALL_SERVICES[*]}"
    return
  fi

  # 이름 변경을 삭제와 추가로 읽어 변경 전후 서비스 경로를 모두 반영한다.
  git diff --name-only --no-renames -z "$before" "$after" -- | select_changed_services
}

# 기존 수동 전체 배포와 단일 서비스 선택을 유지한다.
select_manual_services() {
  local target=$1 service
  if [[ "$target" == all ]]; then
    printf '%s\n' "${ALL_SERVICES[*]}"
    return
  fi

  for service in "${ALL_SERVICES[@]}"; do
    if [[ "$service" == "$target" ]]; then
      printf '%s\n' "$service"
      return
    fi
  done
  printf 'Unknown ECS service: %s\n' "$target" >&2
  return 1
}

case "${1:-}" in
  push) select_push_services "${2:?missing before SHA}" "${3:?missing after SHA}" ;;
  manual) select_manual_services "${2:?missing manual service}" ;;
  *)
    printf 'Usage: %s push <before-sha> <after-sha> | manual <service|all>\n' "$0" >&2
    exit 1
    ;;
esac
