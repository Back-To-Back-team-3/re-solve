#!/usr/bin/env bash
# =================================================================
# Re:Solve Docker Compose 기반 백엔드 서비스 배포 스크립트
# 사용법:
#   ./deploy.sh                          # 전체 백엔드 서비스 배포
#   ./deploy.sh all                      # 전체 백엔드 서비스 배포
#   ./deploy.sh member-service           # 특정 서비스 단독 배포
#   ./deploy.sh api-gateway study-service # 여러 특정 서비스 배포
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

TARGET_SERVICES=()

if [ "$#" -eq 0 ] || [ "$1" = "all" ]; then
    TARGET_SERVICES=("${ALL_BACKEND_SERVICES[@]}")
else
    for arg in "$@"; do
        TARGET_SERVICES+=("$arg")
    done
fi

echo "=========================================================="
echo "🚀 [Deploy] Re:Solve 백엔드 서비스 배포 시작"
echo "  - 배포 대상 서비스: ${TARGET_SERVICES[*]}"
echo "=========================================================="

# 1. .env 파일 확인
if [ ! -f ".env" ]; then
    echo "❌ 오류: 프로젝트 루트 디렉터리에 .env 파일이 없습니다."
    exit 1
fi

# 2. 최신 컨테이너 이미지 풀링
echo "📥 [Deploy] 최신 Docker 이미지 풀링 중..."
for svc in "${TARGET_SERVICES[@]}"; do
    echo "  - pull: ${svc}"
    docker compose pull "${svc}"
done

# 3. 서비스별 순차 롤링 배포 및 헬스체크
echo "🔄 [Deploy] 서비스 순차 재기동 및 헬스체크 진행..."
for svc in "${TARGET_SERVICES[@]}"; do
    echo "----------------------------------------------------------"
    echo "▶ 서비스 재기동: ${svc}"
    docker compose up -d --no-deps "${svc}"

    # 헬스체크 수행
    if ! bash "${SCRIPT_DIR}/health-check.sh" "${svc}" 30 3; then
        echo "❌ [Deploy] '${svc}' 배포 후 헬스체크에 실패하여 배포를 중단합니다."
        exit 1
    fi
done

# 4. 미사용 불필요 이미지 정리
echo "🧹 [Deploy] 사용하지 않는 이전 Docker 이미지 정리..."
docker image prune -f

echo "=========================================================="
echo "✨ [Deploy] 모든 대상 서비스 배포 및 헬스체크 완료!"
echo "  - 완료된 서비스: ${TARGET_SERVICES[*]}"
echo "=========================================================="
