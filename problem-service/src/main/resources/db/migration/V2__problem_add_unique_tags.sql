-- 순번 변경은 같은 트랜잭션에서 임시 음수 순번을 거쳐 처리한다.
ALTER TABLE tags
    ADD CONSTRAINT uk_tags_display_order UNIQUE (display_order);
