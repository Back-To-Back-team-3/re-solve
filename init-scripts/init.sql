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

-- 10. 서비스 계정에 생성된 데이터베이스에 대한 접근 권한 부여
-- docker-entrypoint 환경 변수(MYSQL_USER)로 생성된 계정이 모든 서비스 DB에 접근할 수 있도록 권한 부여
GRANT ALL PRIVILEGES ON `member_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `problem_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `judge_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `contest_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `study_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `notification_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `integration_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `ai_db`.* TO '%'@'%';
GRANT ALL PRIVILEGES ON `analytics_db`.* TO '%'@'%';
FLUSH PRIVILEGES;


