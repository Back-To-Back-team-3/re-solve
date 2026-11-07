#!/usr/bin/env bash
# =================================================================
# Re:Solve 환경별 서비스 시작·안전 중단·상태 점검 스크립트
# 기준 문서:
#   - docs/dev/planning/planning.md > 3.2 요구사항 명세 > T1-09
#   - docs/dev/planning/policy-and-state.md > 9.4 운영·보안 정책 > 환경 중지
#   - docs/dev/specifications/scheduler.md > 0.1 공통 규칙 > 환경 중지
# 사용법:
#   ./service-control.sh start [services...]
#   ./service-control.sh stop [--force] [--include-infra] [services...]
#   ./service-control.sh restart [--force] [services...]
#   ./service-control.sh status [services...]
# =================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${ROOT_DIR}"

# 1. 서비스 목록 및 중단 순서 정의
# policy-and-state.md 9.4: 신규 접수 차단(Gateway) -> 도메인 API -> 채점기 -> DB/인프라 순서
GATEWAY_SERVICES=(
    "api-gateway"
)

DOMAIN_SERVICES=(
    "member-service"
    "problem-service"
    "study-service"
    "contest-service"
    "notification-service"
    "integration-service"
)

EXECUTION_SERVICES=(
    "judge-service"
)

INFRA_SERVICES=(
    "nginx"
    "mysql"
    "redis"
    "localstack"
)

ALL_BACKEND_SERVICES=(
    "${GATEWAY_SERVICES[@]}"
    "${DOMAIN_SERVICES[@]}"
    "${EXECUTION_SERVICES[@]}"
)

ALL_KNOWN_SERVICES=(
    "${ALL_BACKEND_SERVICES[@]}"
    "${INFRA_SERVICES[@]}"
)

ACTION="$1"
shift || true

if [ -z "$ACTION" ]; then
    echo "❌ 오류: 동작(action)을 지정해야 합니다."
    echo "사용법: $0 {start|stop|restart|status} [--force] [--include-infra] [services...]"
    exit 1
fi

FORCE=false
INCLUDE_INFRA=false
TARGET_SERVICES=()

while [ "$#" -gt 0 ]; do
    case "$1" in
        --force)
            FORCE=true
            shift
            ;;
        --include-infra)
            INCLUDE_INFRA=true
            shift
            ;;
        *)
            TARGET_SERVICES+=("$1")
            shift
            ;;
    esac
done

# 화이트리스트 검증 (Command Injection 및 잘못된 서비스명 방지 - H1)
validate_service_name() {
    local svc="$1"
    local found=false
    for known in "${ALL_KNOWN_SERVICES[@]}"; do
        if [ "$svc" = "$known" ]; then
            found=true
            break
        fi
    done
    if [ "$found" = false ]; then
        echo "❌ 오류: 허용되지 않은 서비스명입니다: '${svc}'"
        echo "허용된 서비스: ${ALL_KNOWN_SERVICES[*]}"
        exit 1
    fi
}

IS_CUSTOM_TARGET=true
if [ "${#TARGET_SERVICES[@]}" -eq 0 ] || [ "${TARGET_SERVICES[0]}" = "all" ]; then
    IS_CUSTOM_TARGET=false
    TARGET_SERVICES=("${ALL_BACKEND_SERVICES[@]}")
    if [ "$INCLUDE_INFRA" = true ]; then
        TARGET_SERVICES+=("${INFRA_SERVICES[@]}")
    fi
else
    for svc in "${TARGET_SERVICES[@]}"; do
        validate_service_name "$svc"
    done
fi

STOP_TIMEOUT=30
if [ "$FORCE" = true ]; then
    STOP_TIMEOUT=5
fi

# 2. 진행 중 작업 확인 (policy-and-state.md 9.4, scheduler.md 0.1)
# 진행 중인 시험, 진단, 채점 작업이 있는지 확인하고 보류 여부를 결정한다.
check_active_jobs_before_stop() {
    if [ "$FORCE" = true ]; then
        echo "⚠️ [--force] 강제 종료 옵션이 지정되어 진행 중인 작업 확인을 건너뜁니다."
        return 0
    fi

    # MySQL 컨테이너가 실행 중이지 않으면 DB 점검 불필요
    local mysql_cid
    mysql_cid=$(docker compose ps -q mysql 2>/dev/null || true)
    if [ -z "$mysql_cid" ]; then
        return 0
    fi
    local mysql_status
    mysql_status=$(docker inspect --format='{{.State.Status}}' "$mysql_cid" 2>/dev/null || echo "unknown")
    if [ "$mysql_status" != "running" ]; then
        return 0
    fi

    echo "🔍 [Safety Guard] 진행 중인 사용자 작업 및 채점 대기 상태 점검 중..."

    # DB_USERNAME / DB_PASSWORD 환경변수 확인
    if [ -f ".env" ]; then
        # shellcheck disable=SC1091
        set -a; source .env; set +a
    fi
    local db_user="${DB_USERNAME:-root}"
    local db_pass="${DB_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"

    if [ -z "$db_pass" ]; then
        echo "⚠️ DB 비밀번호 환경변수가 없어 진행 중 작업 상태 쿼리를 건너뜁니다."
        return 0
    fi

    local max_wait_sec=60
    local elapsed=0
    local poll_interval=5

    while [ $elapsed -lt $max_wait_sec ]; do
        local active_exams=0
        local active_judges=0
        local pending_outbox=0

        # 1) 진행 중 시험 점검 (contest_db.exams status = 'IN_PROGRESS')
        active_exams=$(docker compose exec -T mysql mysql -u"$db_user" -p"$db_pass" -N -e "
            SELECT COUNT(*) FROM contest_db.exams WHERE status = 'IN_PROGRESS';
        " 2>/dev/null || echo "0")

        # 2) 진행 중/대기 중 채점 점검 (judge_db.judge_jobs status IN ('PENDING', 'RUNNING'))
        active_judges=$(docker compose exec -T mysql mysql -u"$db_user" -p"$db_pass" -N -e "
            SELECT COUNT(*) FROM judge_db.judge_jobs WHERE status IN ('PENDING', 'RUNNING');
        " 2>/dev/null || echo "0")

        # 3) 미발행 Outbox 점검 (각 DB의 outbox_events WHERE status = 'PENDING')
        pending_outbox=$(docker compose exec -T mysql mysql -u"$db_user" -p"$db_pass" -N -e "
            SELECT (
                (SELECT COUNT(*) FROM member_db.outbox_events WHERE status = 'PENDING') +
                (SELECT COUNT(*) FROM contest_db.outbox_events WHERE status = 'PENDING') +
                (SELECT COUNT(*) FROM judge_db.outbox_events WHERE status = 'PENDING') +
                (SELECT COUNT(*) FROM integration_db.outbox_events WHERE status = 'PENDING')
            );
        " 2>/dev/null || echo "0")

        active_exams=$(echo "$active_exams" | tr -d '[:space:]')
        active_judges=$(echo "$active_judges" | tr -d '[:space:]')
        pending_outbox=$(echo "$pending_outbox" | tr -d '[:space:]')

        if [ "$active_exams" = "0" ] && [ "$active_judges" = "0" ] && [ "$pending_outbox" = "0" ]; then
            echo "✅ [Safety Guard] 진행 중인 사용자 작업 및 미발행 Outbox 0건 확인 완료."
            return 0
        fi

        echo "⏳ [Safety Guard 대기] 진행 중 작업 감지: 시험(${active_exams}건), 채점(${active_judges}건), Outbox 미발행(${pending_outbox}건) - ${elapsed}/${max_wait_sec}초 경과"
        sleep "$poll_interval"
        elapsed=$((elapsed + poll_interval))
    done

    echo "❌ [Stop 보류] policy-and-state.md 9.4에 따라 진행 중인 사용자 시험/채점/Outbox 작업이 존재하여 환경 중지를 보류합니다."
    echo "  - 진행 중 시험: ${active_exams}건"
    echo "  - 진행 중 채점: ${active_judges}건"
    echo "  - 미발행 Outbox: ${pending_outbox}건"
    echo "💡 즉시 중단이 필요한 경우 '--force' 플래그를 사용하여 다시 실행하세요."
    exit 1
}

# 3. 서비스 시작
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

# 4. 서비스 안전 중단
# policy-and-state.md 9.4: 신규 접수 차단(Gateway) -> 도메인 API -> 채점기 -> 인프라 순서
stop_services() {
    echo "=========================================================="
    echo "🛑 [Stop] Re:Solve 서비스 안전 중단 진행"
    echo "  - 대상 서비스: ${TARGET_SERVICES[*]}"
    echo "  - 강제 종료(force): ${FORCE}"
    echo "  - 대기 시간(Grace Period): ${STOP_TIMEOUT}초"
    echo "=========================================================="

    # 커스텀 지정이 아닌 전체 중단일 때만 진행 중 작업 사전 확인
    if [ "$IS_CUSTOM_TARGET" = false ]; then
        # 1단계: 신규 유입 먼저 차단 (api-gateway 중지)
        echo "▶ 1단계: 신규 접수 차단 (api-gateway 중지)..."
        docker compose stop -t "${STOP_TIMEOUT}" api-gateway || true

        # 2단계: 진행 중인 시험·채점·Outbox 작업 점검 및 대기
        check_active_jobs_before_stop

        # 3단계: 도메인 서비스 중단
        echo "▶ 2단계: 도메인 비즈니스 서비스 안전 중단 (SIGTERM)..."
        for svc in "${DOMAIN_SERVICES[@]}"; do
            docker compose stop -t "${STOP_TIMEOUT}" "$svc" || true
        done

        # 4단계: 채점 실행기 중단
        echo "▶ 3단계: 채점 서비스 안전 중단..."
        for svc in "${EXECUTION_SERVICES[@]}"; do
            docker compose stop -t "${STOP_TIMEOUT}" "$svc" || true
        done

        # 5단계: 인프라 컨테이너 중단 (옵션 활성화 시)
        if [ "$INCLUDE_INFRA" = true ]; then
            echo "▶ 4단계: 공통 인프라(DB, Redis 등) 중단..."
            for svc in "${INFRA_SERVICES[@]}"; do
                docker compose stop -t "${STOP_TIMEOUT}" "$svc" || true
            done
        fi
    else
        # 특정 서비스 단독 중단 시
        echo "▶ 대상 서비스 중단 신호 전달 (SIGTERM)..."
        for svc in "${TARGET_SERVICES[@]}"; do
            echo "  - 중단: ${svc} (Grace Period: ${STOP_TIMEOUT}초)"
            docker compose stop -t "${STOP_TIMEOUT}" "${svc}" || true
        done
    fi

    echo "✨ [Stop] 모든 대상 서비스가 안전하게 중단되었습니다."
}

# 5. 서비스 상태 점검
status_services() {
    echo "=========================================================="
    echo "📊 [Status] Re:Solve 서비스 상태 점검"
    echo "  - 점검 대상: ${TARGET_SERVICES[*]}"
    echo "=========================================================="

    echo "▶ Docker Compose 프로세스 현황:"
    docker compose ps "${TARGET_SERVICES[@]}" || true
    echo ""

    echo "▶ 백엔드 서비스 Actuator Health 요약:"
    printf "%-25s %-18s %-12s\n" "SERVICE" "CONTAINER STATUS" "ACTUATOR HEALTH"
    printf "%-25s %-18s %-12s\n" "-------------------------" "------------------" "---------------"

    local all_up=true
    for svc in "${TARGET_SERVICES[@]}"; do
        local cid
        cid=$(docker compose ps -q "$svc" 2>/dev/null || true)
        if [ -z "$cid" ]; then
            printf "%-25s %-18s %-12s\n" "$svc" "NOT FOUND" "DOWN"
            all_up=false
            continue
        fi

        local container_status
        container_status=$(docker inspect --format='{{.State.Status}}' "$cid" 2>/dev/null || echo "unknown")

        local health_result="UNKNOWN"
        if [ "$container_status" = "running" ]; then
            # Actuator 헬스 엔드포인트가 있는 백엔드 서비스만 검사
            if [[ " ${ALL_BACKEND_SERVICES[*]} " =~ " ${svc} " ]]; then
                local resp
                resp=$(docker exec "$cid" sh -c "curl -s -f http://127.0.0.1:8080/actuator/health 2>/dev/null || wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null" || true)
                if echo "$resp" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
                    health_result="UP"
                else
                    health_result="NOT_UP"
                    all_up=false
                fi
            else
                health_result="INFRA_UP"
            fi
        else
            health_result="STOPPED"
            all_up=false
        fi

        printf "%-25s %-18s %-12s\n" "$svc" "$container_status" "$health_result"
    done

    echo ""
    if [ "$all_up" = true ]; then
        echo "✅ 모든 대상 서비스가 정상 기동(UP) 상태입니다."
    else
        echo "⚠️ 일부 서비스가 중단(STOPPED) 또는 비정상(NOT_UP/DOWN) 상태입니다."
    fi
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
        echo "사용법: $0 {start|stop|restart|status} [--force] [--include-infra] [services...]"
        exit 1
        ;;
esac
