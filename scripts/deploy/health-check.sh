#!/usr/bin/env bash
# =================================================================
# Re:Solve 서비스 헬스체크 스크립트
# 사용법: ./health-check.sh <service_name> [max_retries] [retry_interval_sec]
# =================================================================

set -eo pipefail

SERVICE_NAME="$1"
MAX_RETRIES="${2:-30}"
RETRY_INTERVAL="${3:-3}"

if [ -z "$SERVICE_NAME" ]; then
    echo "❌ 오류: 서비스명이 지정되지 않았습니다."
    echo "사용법: $0 <service_name> [max_retries] [retry_interval_sec]"
    exit 1
fi

echo "🔍 [Health Check] '${SERVICE_NAME}' 서비스 상태 점검 시작 (최대 ${MAX_RETRIES}회 시도, 간격 ${RETRY_INTERVAL}초)..."

CONTAINER_ID=$(docker compose ps -q "$SERVICE_NAME" 2>/dev/null || true)
if [ -z "$CONTAINER_ID" ]; then
    echo "❌ 오류: 컨테이너 '${SERVICE_NAME}'을(를) 찾을 수 없습니다."
    exit 1
fi

COUNT=0
while [ $COUNT -lt "$MAX_RETRIES" ]; do
    COUNT=$((COUNT + 1))
    
    # 컨테이너 실행 상태 확인 (running 여부)
    STATUS=$(docker inspect --format='{{.State.Status}}' "$CONTAINER_ID" 2>/dev/null || echo "unknown")
    
    if [ "$STATUS" = "running" ]; then
        # Actuator Health 엔드포인트 응답 검사 시도
        HEALTH_STATUS=$(docker exec "$CONTAINER_ID" sh -c "curl -s -f http://127.0.0.1:8080/actuator/health 2>/dev/null || wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null" || true)
        
        if echo "$HEALTH_STATUS" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
            echo "✅ [Health Check] '${SERVICE_NAME}' 상태: UP (시도 ${COUNT}회차 성공)"
            exit 0
        fi
    elif [ "$STATUS" = "exited" ] || [ "$STATUS" = "dead" ]; then
        echo "❌ [Health Check] '${SERVICE_NAME}' 컨테이너가 예기치 않게 종료되었습니다 (상태: ${STATUS})."
        docker compose logs --tail 30 "$SERVICE_NAME"
        exit 1
    fi

    echo "  - 대기 중... (${COUNT}/${MAX_RETRIES}): 컨테이너 상태='${STATUS}'"
    sleep "$RETRY_INTERVAL"
done

echo "❌ [Health Check] '${SERVICE_NAME}' 헬스체크 타임아웃 (${MAX_RETRIES}회 초과)."
echo "--- 최근 컨테이너 로그 ---"
docker compose logs --tail 50 "$SERVICE_NAME"
exit 1
