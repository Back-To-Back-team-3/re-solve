#!/bin/bash
# =================================================================
# Re:Solve LocalStack AWS 인프라(S3, SNS, SQS) 자동 초기화 스크립트
# LocalStack 컨테이너 부팅 완료 시 /etc/localstack/init/ready.d/ 에서 자동 실행됩니다.
# =================================================================

set -eo pipefail

REGION="${AWS_DEFAULT_REGION:-ap-northeast-2}"
echo "[LocalStack Init] AWS 인프라 리소스 초기화 시작 (Region: ${REGION})..."

# -----------------------------------------------------------------
# 1. S3 버킷 생성
# 기획서: 문제 본문, 이미지, 테스트케이스(회차별 불변 경로), 첨부 파일 저장용
# -----------------------------------------------------------------
S3_BUCKETS=(
    "resolve-problem-storage"
)

echo "[S3] 버킷 생성 중..."
for bucket in "${S3_BUCKETS[@]}"; do
    if awslocal s3api head-bucket --bucket "${bucket}" 2>/dev/null; then
        echo "  - S3 버킷 이미 존재함: ${bucket}"
    else
        awslocal s3api create-bucket \
            --bucket "${bucket}" \
            --create-bucket-configuration LocationConstraint="${REGION}" 2>/dev/null || \
        awslocal s3api create-bucket --bucket "${bucket}"
        echo "  - S3 버킷 생성 완료: ${bucket}"
    fi
done

# -----------------------------------------------------------------
# 2. SNS 도메인 이벤트 토픽 생성
# 기획서: 서비스 간 비동기 도메인 이벤트 발행을 위한 메인 토픽
# -----------------------------------------------------------------
SNS_TOPIC_NAME="resolve-domain-events-topic"

echo "[SNS] 도메인 이벤트 토픽 생성 중..."
TOPIC_ARN=$(awslocal sns create-topic --name "${SNS_TOPIC_NAME}" --query 'TopicArn' --output text)
echo "  - SNS 토픽 생성 완료: ${TOPIC_ARN}"

# -----------------------------------------------------------------
# 3. SQS 큐 및 DLQ(Dead Letter Queue) 생성 함수 정의
# -----------------------------------------------------------------
create_queue_with_dlq() {
    local queue_name="$1"
    local dlq_name="${queue_name}-dlq"

    # DLQ 생성
    local dlq_url
    dlq_url=$(awslocal sqs create-queue --queue-name "${dlq_name}" --query 'QueueUrl' --output text)
    local dlq_arn
    dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "${dlq_url}" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

    # RedrivePolicy(DLQ 연결) 설정 - 최대 3회 수신 실패 시 DLQ로 격리
    local redrive_policy="{\"deadLetterTargetArn\":\"${dlq_arn}\",\"maxReceiveCount\":\"3\"}"
    
    # 메인 큐 생성 (DLQ 지정)
    local queue_url
    queue_url=$(awslocal sqs create-queue \
        --queue-name "${queue_name}" \
        --attributes "{\"RedrivePolicy\":$(echo -n "${redrive_policy}" | jq -R .)}" \
        --query 'QueueUrl' --output text 2>/dev/null || \
        awslocal sqs create-queue \
        --queue-name "${queue_name}" \
        --attributes "RedrivePolicy={\"deadLetterTargetArn\":\"${dlq_arn}\",\"maxReceiveCount\":\"3\"}" \
        --query 'QueueUrl' --output text)

    local queue_arn
    queue_arn=$(awslocal sqs get-queue-attributes --queue-url "${queue_url}" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

    echo "  - SQS 큐 생성 완료: ${queue_name} (DLQ: ${dlq_name})"

    # 전역 반환용 변수
    CREATED_QUEUE_ARN="${queue_arn}"
    CREATED_QUEUE_URL="${queue_url}"
}

# -----------------------------------------------------------------
# 4. 단독 작업 큐 생성: exam-submission-queue (시험/대회 채점 요청 큐)
# contest-service -> SQS exam-submission-queue -> judge-service
# -----------------------------------------------------------------
echo "[SQS] 시험 채점 요청 큐 생성 중..."
create_queue_with_dlq "exam-submission-queue"

# -----------------------------------------------------------------
# 5. 도메인 이벤트 수신용 SQS 큐 생성 및 SNS 토픽 구독(Fan-out) 연결
# 5개 서비스: notification, contest, study, member, integration
# -----------------------------------------------------------------
DOMAIN_QUEUES=(
    "notification-queue"
    "contest-queue"
    "study-queue"
    "member-queue"
    "integration-queue"
)

echo "[SQS & SNS] 도메인 이벤트 큐 생성 및 SNS 팬아웃 구독 연결 중..."
for q in "${DOMAIN_QUEUES[@]}"; do
    create_queue_with_dlq "${q}"
    
    # SNS 토픽에 SQS 큐 구독(Subscription) 등록
    awslocal sns subscribe \
        --topic-arn "${TOPIC_ARN}" \
        --protocol sqs \
        --notification-endpoint "${CREATED_QUEUE_ARN}" \
        --attributes '{"RawMessageDelivery":"true"}' > /dev/null

    echo "    - SNS 토픽 구독 완료: ${SNS_TOPIC_NAME} -> ${q}"
done

echo "[LocalStack Init] 모든 S3 버킷, SNS 토픽, SQS 큐 초기화 완료!"
