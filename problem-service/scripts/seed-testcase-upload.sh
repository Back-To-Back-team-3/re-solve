#!/bin/bash
# =================================================================
# Re:Solve 시드 테스트 콘텐츠 LocalStack S3 업로드 스크립트
#
# 로컬 디렉터리의 *.json 파일을 S3 버킷(resolve-problem-storage)에
# `test-cases/{파일명}` 키로 업로드하고, 파일별 메타 정보(객체 키·
# SHA-256 체크섬·바이트 크기)를 CSV로 표준 출력한다.
#
# 이 스크립트는 문제별 실제 테스트 값을 담지 않는다(비공개 유지).
# 호출자가 업로드 순서를 object_key의 PK(`problem_test_assets.id`)
# 순서와 맞추려면, 디렉터리 안 파일명을 미리 "{예상 PK}.json"으로
# 준비해 두어야 한다.
#
# 사용법: ./seed-testcase-upload.sh <로컬 JSON 디렉터리>
# 출력: object_key,sha256,size_bytes (표준 출력, CSV, 헤더 포함)
# =================================================================
set -eo pipefail

SOURCE_DIR="$1"
BUCKET="${TESTCASE_S3_BUCKET:-resolve-problem-storage}"
KEY_PREFIX="test-cases"
ENDPOINT="${LOCALSTACK_ENDPOINT:-http://localhost:4566}"
REGION="${AWS_DEFAULT_REGION:-ap-northeast-2}"

if [ -z "${SOURCE_DIR}" ] || [ ! -d "${SOURCE_DIR}" ]; then
    echo "사용법: $0 <로컬 JSON 디렉터리>" >&2
    exit 1
fi

if command -v awslocal >/dev/null 2>&1; then
    # awslocal은 ENDPOINT가 아니라 LOCALSTACK_HOST(스킴 없는 host:port)로 대상을 정하므로,
    # LOCALSTACK_ENDPOINT를 명시적으로 넘겨도 두 실행 경로가 같은 대상을 가리키도록 맞춘다.
    export LOCALSTACK_HOST="${LOCALSTACK_HOST:-${ENDPOINT#http://}}"
    export LOCALSTACK_HOST="${LOCALSTACK_HOST#https://}"
    AWS_CMD=(awslocal)
else
    # awslocal이 없는 환경(예: 로컬 쉘)에서는 LocalStack 기본 테스트 자격 증명을 쓴다.
    export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
    export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
    AWS_CMD=(aws --endpoint-url "${ENDPOINT}" --region "${REGION}")
fi

shopt -s nullglob
files=("${SOURCE_DIR}"/*.json)
if [ ${#files[@]} -eq 0 ]; then
    echo "[seed-testcase-upload] ${SOURCE_DIR}에 업로드할 *.json 파일이 없습니다." >&2
    exit 1
fi

echo "[seed-testcase-upload] ${#files[@]}개 파일을 s3://${BUCKET}/${KEY_PREFIX}/ 에 업로드합니다." >&2

echo "object_key,sha256,size_bytes"
for file in "${files[@]}"; do
    filename="$(basename "${file}")"
    object_key="${KEY_PREFIX}/${filename}"
    sha256="$(sha256sum "${file}" | awk '{print $1}')"
    size_bytes="$(wc -c < "${file}" | tr -d ' ')"

    "${AWS_CMD[@]}" s3 cp "${file}" "s3://${BUCKET}/${object_key}" --only-show-errors

    echo "${object_key},${sha256},${size_bytes}"
done
