#!/usr/bin/env bash
# =================================================================
# Re:Solve 환경별 서비스 시작·안전 중단·상태 점검 스크립트 (T1-09)
# 사용법:
#   ./service-control.sh start [services...]
#   ./service-control.sh stop [--force] [services...]
#   ./service-control.sh restart [--force] [services...]
#   ./service-control.sh status [services...]
# =================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${ROOT_DIR}"

ALL_BACKEND_SERVICES=(
    "api-gateway"
    "member-service"
    "problem-service"
    "judge-service"
    "contest-service"
    "study-service"
    "notification-service"
    "integration-service"
)

ACTION="$1"
shift || true

if [ -z "$ACTION" ]; then
    echo "❌ 오류: 동작(action)을 지정해야 합니다."
    echo "사용법: $0 {start|stop|restart|status} [--force] [services...]"
    exit 1
fi

FORCE=false
TARGET_SERVICES=()

while [ "$#" -gt 0 ]; do
    case "$1" in
        --force)
            FORCE=true
            shift
            ;;
        *)
            TARGET_SERVICES+=("$1")
            shift
            ;;
    esac
done

if [ "${#TARGET_SERVICES[@]}" -eq 0 ] || [ "${TARGET_SERVICES[0]}" = "all" ]; then
    TARGET_SERVICES=("${ALL_BACKEND_SERVICES[@]}")
fi

STOP_TIMEOUT=30
if [ "$FORCE" = true ]; then
    STOP_TIMEOUT=5
fi

# 1. 서비스 시작
start_services() {
    echo "=========================================================="
    echo "🚀 [Start] Re:Solve 서비스 시작 진행"
    echo "  - 대상 서비스: ${TARGET_SERVICES[*]}"
    echo "=========================================================="

    if [ ! -f ".env" ]; then
        echo "❌ 오류: 프로젝트 루트 디렉터리에 .env 파일이 없습니다."
        exit 1
    fi

    echo "▶ Docker Compose 컨테이너 기동..."
    docker compose up -d "${TARGET_SERVICES[@]}"

    echo "🔍 서비스 헬스체크 대기..."
    local failed=0
    for svc in "${TARGET_SERVICES[@]}"; do
        if [[ " ${ALL_BACKEND_SERVICES[*]} " =~ " ${svc} " ]]; then
            if ! bash "${SCRIPT_DIR}/health-check.sh" "${svc}" 30 3; then
                echo "❌ [Start] '${svc}' 헬스체크 실패"
                failed=1
            fi
        fi
    done

    if [ "$failed" -ne 0 ]; then
        echo "❌ [Start] 일부 서비스 기동 또는 헬스체크에 실패했습니다."
        exit 1
    fi

    echo "✨ [Start] 모든 대상 서비스가 성공적으로 기동되었습니다."
}

# 2. 서비스 안전 중단
stop_services() {
    echo "=========================================================="
    echo "🛑 [Stop] Re:Solve 서비스 안전 중단 진행"
    echo "  - 대상 서비스: ${TARGET_SERVICES[*]}"
    echo "  - 강제 종료(force): ${FORCE}"
    echo "  - 대기 시간(Grace Period): ${STOP_TIMEOUT}초"
    echo "=========================================================="

    echo "▶ 진행 중인 작업 완료 대기 및 컨테이너 종료 (SIGTERM)..."
    for svc in "${TARGET_SERVICES[@]}"; do
        echo "  - 중단 신호 전달: ${svc} (최대 ${STOP_TIMEOUT}초 대기)"
        docker compose stop -t "${STOP_TIMEOUT}" "${svc}"
    done

    echo "✨ [Stop] 모든 대상 서비스가 안전하게 중단되었습니다."
}

# 3. 서비스 상태 점검
status_services() {
    echo "=========================================================="
    echo "📊 [Status] Re:Solve 서비스 상태 점검"
    echo "  - 점검 대상: ${TARGET_SERVICES[*]}"
    echo "=========================================================="

    echo "▶ Docker Compose 프로세스 현황:"
    docker compose ps "${TARGET_SERVICES[@]}" || true
    echo ""

    echo "▶ 백엔드 서비스 Actuator Health 요약:"
    printf "%-25s %-15s %-10s\n" "서비스명" "컨테이너 상태" "헬스 응답"
    printf "%-25s %-15s %-10s\n" "-------------------------" "---------------" "----------"

    for svc in "${TARGET_SERVICES[@]}"; do
        local cid
        cid=$(docker compose ps -q "$svc" 2>/dev/null || true)
        if [ -z "$cid" ]; then
            printf "%-25s %-15s %-10s\n" "$svc" "NOT FOUND" "DOWN"
            continue
        fi

        local container_status
        container_status=$(docker inspect --format='{{.State.Status}}' "$cid" 2>/dev/null || echo "unknown")

        local health_result="UNKNOWN"
        if [ "$container_status" = "running" ]; then
            local resp
            resp=$(docker exec "$cid" sh -c "curl -s -f http://127.0.0.1:8080/actuator/health 2>/dev/null || wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null" || true)
            if echo "$resp" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
                health_result="UP"
            else
                health_result="NOT_UP"
            fi
        else
            health_result="STOPPED"
        fi

        printf "%-25s %-15s %-10s\n" "$svc" "$container_status" "$health_result"
    done
}

case "$ACTION" in
    start)
        start_services
        ;;
    stop)
        stop_services
        ;;
    restart)
        stop_services
        start_services
        ;;
    status)
        status_services
        ;;
    *)
        echo "❌ 지원하지 않는 동작입니다: ${ACTION}"
        echo "사용법: $0 {start|stop|restart|status} [--force] [services...]"
        exit 1
        ;;
esac
