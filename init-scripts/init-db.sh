#!/bin/bash
set -eo pipefail

# =================================================================
# Re:Solve MSA 서비스별 데이터베이스(스키마) 및 권한 자동 초기화 스크립트
# MySQL 컨테이너 최초 기동 시 /docker-entrypoint-initdb.d/ 에서 자동 실행됩니다.
# =================================================================

echo "[MySQL Init] 서비스별 데이터베이스 및 사용자 권한 초기화 시작..."

DATABASES=(
  "member_db"
  "problem_db"
  "judge_db"
  "contest_db"
  "study_db"
  "notification_db"
  "integration_db"
  "ai_db"
  "analytics_db"
)

# 1. 각 서비스별 데이터베이스 생성
for db in "${DATABASES[@]}"; do
  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e "CREATE DATABASE IF NOT EXISTS \`${db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  echo "  - 데이터베이스 생성 완료: ${db}"
done

# 2. MYSQL_USER 계정에 모든 서비스 데이터베이스 권한 부여
if [ -n "${MYSQL_USER}" ]; then
  echo "[MySQL Init] '${MYSQL_USER}'@'%' 사용자에게 서비스 데이터베이스 권한 부여 중..."
  for db in "${DATABASES[@]}"; do
    mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e "GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${MYSQL_USER}'@'%';"
  done
  mysql -u root -p"${MYSQL_ROOT_PASSWORD}" -e "FLUSH PRIVILEGES;"
  echo "  - 권한 부여 완료: '${MYSQL_USER}'@'%'"
fi

echo "[MySQL Init] 데이터베이스 초기화 완료!"
