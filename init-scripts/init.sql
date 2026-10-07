-- Re:Solve MSA 서비스별 데이터베이스(스키마) 자동 생성 스크립트
-- MySQL 컨테이너 최초 기동 시 /docker-entrypoint-initdb.d/ 를 통해 자동 실행됩니다.

-- 1. 회원 서비스 (member-service)
CREATE DATABASE IF NOT EXISTS `member_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 2. 문제 서비스 (problem-service)
CREATE DATABASE IF NOT EXISTS `problem_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 3. 채점 서비스 (judge-service)
CREATE DATABASE IF NOT EXISTS `judge_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 4. 대회/모의코테 서비스 (contest-service)
CREATE DATABASE IF NOT EXISTS `contest_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 5. 스터디 서비스 (study-service)
CREATE DATABASE IF NOT EXISTS `study_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 6. 알림 서비스 (notification-service)
CREATE DATABASE IF NOT EXISTS `notification_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 7. 연동 서비스 (integration-service)
CREATE DATABASE IF NOT EXISTS `integration_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 8. AI 서비스 (ai-service)
CREATE DATABASE IF NOT EXISTS `ai_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 9. 분석 서비스 (analytics-service)
CREATE DATABASE IF NOT EXISTS `analytics_db`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
