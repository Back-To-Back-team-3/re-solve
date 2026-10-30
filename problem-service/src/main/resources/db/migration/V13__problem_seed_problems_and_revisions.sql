-- 시드 문제 30개(제목·난이도·function_spec)는 .personal/D-2_problem-seed/seed-problem/seed-problems-v1.md를 그대로 옮겼다.
-- 검증(problem_validations) 파이프라인이 아직 없어 정상 상태 전이 없이 visibility를 바로 PUBLIC으로 지정한다(그릴링 Q1).
-- problem_revision_tags(회차-태그 연결)는 T2-03 범위로 남겨 이번 시드에서는 만들지 않는다(그릴링 Q2).

INSERT INTO problems (author_id, scope, visibility, row_version, published_at, created_at, updated_at) VALUES
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'),
    (1, 'GENERAL', 'PUBLIC', 1, '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000');

-- problem_id는 problems가 이 마이그레이션에서 처음 채워지는 테이블이라 AUTO_INCREMENT가 삽입 순서대로 1~30이 된다는 전제를 쓴다.
INSERT INTO problem_body_assets (
    problem_id, owner_id, status, object_key, sha256, size_bytes,
    upload_expires_at, uploaded_at, upload_finished_at, created_at, updated_at
)
SELECT
    p.id, 1, 'LINKED', CONCAT('seed/problem-body/', LPAD(p.id, 3, '0'), '.md'), REPEAT('a', 64), 1024,
    '2026-11-01 00:00:00.000000', '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000',
    '2026-10-31 00:00:00.000000', '2026-10-31 00:00:00.000000'
FROM problems p
WHERE p.created_at = '2026-10-31 00:00:00.000000'
ORDER BY p.id;

-- body_asset_id도 problem_body_assets가 이 마이그레이션에서 처음 채워져 problem_id와 1:1로 같은 순서(1~30)를 갖는다는 전제를 쓴다.
INSERT INTO problem_revisions (
    problem_id, revision_number, body_asset_id, title, difficulty, function_spec,
    time_limit_ms, memory_limit_kb, wall_time_limit_ms, output_limit_kb,
    author_id, idempotency_key, request_hash, response_row_version, created_at
)
SELECT p.id, 1, p.id, r.title, r.difficulty, r.function_spec,
    2000, 262144, 7000, 64, 1, r.idempotency_key, REPEAT('b', 64), 1, '2026-10-31 00:00:00.000000'
FROM problems p
JOIN (
    SELECT 1 AS seq, '스터디 출석 체크인 집계' AS title, 1 AS difficulty,
        '{"name":"solution","parameters":[{"name":"checkIns","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}' AS function_spec,
        'seed-problem-001' AS idempotency_key
    UNION ALL SELECT 2, '제출 코드 괄호 검증', 2,
        '{"name":"solution","parameters":[{"name":"tokens","type":{"kind":"ARRAY","elementType":{"kind":"STRING"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-002'
    UNION ALL SELECT 3, '통계 배치 샤드 키 분배', 2,
        '{"name":"solution","parameters":[{"name":"cards","type":{"kind":"STRING"}},{"name":"shardCount","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-003'
    UNION ALL SELECT 4, '추천 알림 전파 단계', 2,
        '{"name":"solution","parameters":[{"name":"memberCount","type":{"kind":"INT32"}},{"name":"follows","type":{"kind":"ARRAY","elementType":{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"INT32"}]}}},{"name":"sourceId","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-004'
    UNION ALL SELECT 5, '스터디 모임 시간대 배정', 3,
        '{"name":"solution","parameters":[{"name":"meetingStarts","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"meetingEnds","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-005'
    UNION ALL SELECT 6, '제출 코드 해시 충돌 탐지', 2,
        '{"name":"solution","parameters":[{"name":"lengths","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"hashes","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-006'
    UNION ALL SELECT 7, '회원 약점 태그 Top-2 추출', 3,
        '{"name":"solution","parameters":[{"name":"tagIds","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"isCorrect","type":{"kind":"ARRAY","elementType":{"kind":"BOOLEAN"}}}],"returnType":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}',
        'seed-problem-007'
    UNION ALL SELECT 8, '문제 수정 되돌리기 처리', 1,
        '{"name":"solution","parameters":[{"name":"actions","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-008'
    UNION ALL SELECT 9, '힌트 캐시 만료까지 남은 요청 수', 3,
        '{"name":"solution","parameters":[{"name":"requestCounts","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}',
        'seed-problem-009'
    UNION ALL SELECT 10, '통계 리포트 최소 변동 구간 탐색', 1,
        '{"name":"solution","parameters":[{"name":"dailyActiveCounts","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"windowSize","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-010'
    UNION ALL SELECT 11, '스터디 문제집 태그 커버리지 최소화', 3,
        '{"name":"solution","parameters":[{"name":"tagMasks","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"targetMask","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-011'
    UNION ALL SELECT 12, '연관 태그 도달 가능 여부', 1,
        '{"name":"solution","parameters":[{"name":"tagCount","type":{"kind":"INT32"}},{"name":"relations","type":{"kind":"ARRAY","elementType":{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"INT32"}]}}},{"name":"startTag","type":{"kind":"INT32"}},{"name":"targetTag","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-012'
    UNION ALL SELECT 13, 'AI 힌트 단계 진행 경로 수', 3,
        '{"name":"solution","parameters":[{"name":"stageCount","type":{"kind":"INT32"}},{"name":"transitions","type":{"kind":"ARRAY","elementType":{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"INT32"}]}}},{"name":"startStage","type":{"kind":"INT32"}},{"name":"endStage","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-013'
    UNION ALL SELECT 14, '알림 발송 배치 최소화', 1,
        '{"name":"solution","parameters":[{"name":"requestTimes","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"batchWindow","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-014'
    UNION ALL SELECT 15, '재채점 작업 순서 최소화', 2,
        '{"name":"solution","parameters":[{"name":"durations","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-015'
    UNION ALL SELECT 16, '문제 공개 순서 정렬', 1,
        '{"name":"solution","parameters":[{"name":"titles","type":{"kind":"ARRAY","elementType":{"kind":"STRING"}}},{"name":"publishedAts","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"ARRAY","elementType":{"kind":"STRING"}}}',
        'seed-problem-016'
    UNION ALL SELECT 17, '스터디 리더보드 순위 산정', 2,
        '{"name":"solution","parameters":[{"name":"memberIds","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"solvedCounts","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"lastActiveAts","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}',
        'seed-problem-017'
    UNION ALL SELECT 18, 'AI 힌트 워커 매칭 최대화', 3,
        '{"name":"solution","parameters":[{"name":"requiredPowers","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"workerPowers","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-018'
    UNION ALL SELECT 19, '채점 대기열 상위 K개 추출', 1,
        '{"name":"solution","parameters":[{"name":"priorities","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"k","type":{"kind":"INT32"}}],"returnType":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}',
        'seed-problem-019'
    UNION ALL SELECT 20, '통계 집계 작업 병합 최소 비용', 2,
        '{"name":"solution","parameters":[{"name":"workSizes","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-020'
    UNION ALL SELECT 21, '채점 작업 평균 대기시간 최소화', 3,
        '{"name":"solution","parameters":[{"name":"requestTimes","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"handleTimes","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-021'
    UNION ALL SELECT 22, 'AI 힌트 단계 진행 방법의 수', 1,
        '{"name":"solution","parameters":[{"name":"stageCount","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-022'
    UNION ALL SELECT 23, '스터디 문제집 학습 가치 최대화', 2,
        '{"name":"solution","parameters":[{"name":"costs","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"values","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"timeLimit","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-023'
    UNION ALL SELECT 24, '회원 실력 점수 최장 상승 기록', 3,
        '{"name":"solution","parameters":[{"name":"scores","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-024'
    UNION ALL SELECT 25, '난이도 이하 문제 수 조회', 1,
        '{"name":"solution","parameters":[{"name":"difficulties","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"maxDifficulty","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-025'
    UNION ALL SELECT 26, '알림 스피커 최소 간격 최대화', 2,
        '{"name":"solution","parameters":[{"name":"positions","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"speakerCount","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-026'
    UNION ALL SELECT 27, '통계 로그 보관 공간 최대 확보 기준', 3,
        '{"name":"solution","parameters":[{"name":"logSizes","type":{"kind":"ARRAY","elementType":{"kind":"INT32"}}},{"name":"requiredSpace","type":{"kind":"INT64"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-027'
    UNION ALL SELECT 28, '회원 추천 네트워크 그룹 수', 1,
        '{"name":"solution","parameters":[{"name":"memberCount","type":{"kind":"INT32"}},{"name":"follows","type":{"kind":"ARRAY","elementType":{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"INT32"}]}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-028'
    UNION ALL SELECT 29, '스터디 네트워크 최소 연결 비용', 2,
        '{"name":"solution","parameters":[{"name":"studyCount","type":{"kind":"INT32"}},{"name":"edges","type":{"kind":"ARRAY","elementType":{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"INT32"},{"kind":"INT32"}]}}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-029'
    UNION ALL SELECT 30, '알림 가중 그래프 최대 전달 시간', 3,
        '{"name":"solution","parameters":[{"name":"nodeCount","type":{"kind":"INT32"}},{"name":"edges","type":{"kind":"ARRAY","elementType":{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"INT32"},{"kind":"INT32"}]}}},{"name":"sourceId","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}}',
        'seed-problem-030'
) r ON r.seq = p.id
WHERE p.created_at = '2026-10-31 00:00:00.000000'
ORDER BY p.id;
