-- 이름이 이미 있는 태그는 건너뛰고, display_order는 기존 최댓값 다음부터 연속으로 이어 붙인다.
-- 정상 관리 흐름의 display_order는 0부터 연속이므로, 기존 태그가 있는 환경에서 0부터 고정하면
-- uk_tags_display_order 충돌로 Flyway 적용 자체가 실패할 수 있다.
SET @seed_tags_start_order = (SELECT COALESCE(MAX(display_order) + 1, 0) FROM tags);

INSERT INTO tags (name, display_order, created_at, updated_at, version)
SELECT
    t.name,
    @seed_tags_start_order + ROW_NUMBER() OVER (ORDER BY t.seq) - 1,
    '2026-10-31 00:00:00.000000',
    '2026-10-31 00:00:00.000000',
    0
FROM (
    SELECT 1 AS seq, '해시' AS name
    UNION ALL SELECT 2, '스택/큐'
    UNION ALL SELECT 3, '완전탐색'
    UNION ALL SELECT 4, 'DFS/BFS'
    UNION ALL SELECT 5, '탐욕법'
    UNION ALL SELECT 6, '정렬'
    UNION ALL SELECT 7, '힙'
    UNION ALL SELECT 8, 'DP'
    UNION ALL SELECT 9, '이분탐색'
    UNION ALL SELECT 10, '그래프'
) t
WHERE NOT EXISTS (SELECT 1 FROM tags existing WHERE existing.name = t.name)
ORDER BY t.seq;
