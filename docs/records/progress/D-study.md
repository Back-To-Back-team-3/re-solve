# D 담당 개발 계획서

> 소유 도메인: 스터디·추천·AI / `study-service`, `ai-service` 후보
> 관리: D 담당 — 카테고리 완료(PR 머지)마다 업데이트
> 추가 역할: AI 인프라 담당 — Sprint 마무리마다 overview.md·타 담당 파일 갱신

## 마일스톤 현황

| 마일스톤 | 기능 IDs | 상태 |
| --- | --- | --- |
| P0 | T2-02·T2-04 | ✅ |
| P1 | T4-01·T4-02·T4-03·T4-04 | ⚡ (D-6 미착수 이월, D-7 일부 완료) |
| P2 | T4-05·T4-06·T4-07·T4-08 | ⬜ |
| P3 | T4-09·T4-10 | ⬜ |

## 사이클 표

| 사이클 | 이슈 제목 | 의존 | 시점 | 상태 |
| --- | --- | --- | --- | --- |
| **D-0** | AI 협업 인프라·문서 기준 | - | Sprint-0 | ✅ |
| D-0-1 | `docs(docs): 개발 문서 초기 등록` (#18) | - | Sprint-0 | ✅ PR #20 |
| D-0-4 | `docs(docs): 서비스 간 인증 결정 반영` (#44) | D-0-1 | Sprint-1 | ✅ PR #45 |
| D-0-2 | `chore(docs): AI 협업 문서 초기 등록` (#19) | D-0-4 | Sprint-1 | ✅ PR #60 |
| D-0-3 | `docs(docs): AI 협업 프롬프트 파이프라인 작성` (#21) | D-0-2 | Sprint-1 | ✅ PR #81 |
| D-0-5 | `docs(docs): 백엔드 코드 구조 기준 추가 및 피드백 반영` (#51) | D-0-3 | Sprint-2 | ✅ PR #95 (→ #86 대체) |
| D-0-6 | `docs(docs): 개발 진행 기록 추가` (#92) | D-0-5 | Sprint-2 | ✅ PR #116 |
| D-0-7 | `docs(docs): AI 협업 인프라 문서·프롬프트 일괄 개선` (#126) | D-0-6 | Sprint-2 | ✅ PR #131 |
| **D-1** | study 도메인 골격 | D-0-2 | Sprint-2 | ✅ |
| D-1-1 | `chore(study): 공통 모듈 적용 및 DB·Flyway 기본 설정` (#73) | - | Sprint-2 | ✅ PR #119 |
| D-1-2 | `chore(study): 공통 보안 체인 연결` (#120) | D-1-1 | Sprint-2 | ✅ PR #121 |
| D-1-3 | `feat(study): 공통·스터디·가입 엔티티·Flyway 구현` (#123) | D-1-1 | Sprint-2 | ✅ PR #142 |
| D-1-4 | `feat(study): 문제집 엔티티·Flyway 구현` (#124) | D-1-1 | Sprint-2 | ✅ PR #143 |
| D-1-5 | `feat(study): 회원 복제본 엔티티·Flyway 구현` (#125) | D-1-1 | Sprint-2 | ✅ PR #145 |
| **D-2** | T2-04 시드 데이터 [P0 · D 독립 착수] | D-2-2 | Sprint-1 | ✅ |
| D-2-1 | `feat(problem): 시드 문제 내용 출제 (30문제, 자체 창작)` (#176) | - | Sprint-1 | ✅ |
| D-2-2 | `feat(problem): Problem·ProblemRevision·본문 자산 엔티티 및 Flyway` (#196) | D-2-1 | Sprint-1 | ✅ PR #208 |
| D-2-3 | `feat(problem): 시드 태그·문제·회차 Flyway SQL` (#197) | D-3-1 | Sprint-1 | ✅ PR #257 |
| D-2-4 | `feat(judge): 채점 골든셋 정답·오답 코드 세트 작성` (#198) | - | Sprint-1 | ✅ PR #275 |
| D-2-5 | `feat(problem): 문제 엔티티 Repository 및 조회 메서드 추가` (#203) | D-2-2 | Sprint-1 | ✅ PR #234 |
| **D-3** | T2-02 테스트케이스 [P0 · D 독립 착수] | D-2-5 | Sprint-1 | ✅ |
| D-3-1 | `feat(problem): 테스트케이스 엔티티·Flyway 마이그레이션` (#199) | D-2-5 | Sprint-1 | ✅ PR #256 |
| D-3-2 | `feat(problem): 시드 테스트케이스 등록` (#252) | D-2-3 | Sprint-1 | ✅ PR #260 |
| **D-4** | T4-01 스터디 탐색 [P1] | D-1-3 | Sprint-2 | ✅ |
| D-4-1 | `feat(study): 스터디 목록·조건 검색 API` (#173) | D-1-3 | Sprint-2 | ✅ PR #177 |
| D-4-2 | `feat(study): 스터디 상세 조회 API` (#174) | D-4-1 | Sprint-2 | ✅ PR #179 |
| D-4-3 | `feat(study): 내 스터디 목록 API` (#175) | D-4-1 | Sprint-2 | ✅ PR #183 |
| **D-5** | T4-02 스터디 생성·가입 [P1] | D-4 | Sprint-2 | ✅ |
| D-5-1 | `feat(study): 스터디 생성 API + 동시성 보장` (#283) | D-4-1 | Sprint-2 | ✅ PR #330 |
| D-5-2 | `feat(study): 가입 신청·즉시 가입·취소·탈퇴 API` (#285) | D-5-1 | Sprint-2 | ✅ PR #335 |
| D-5-3 | `feat(study): 구성원·대기 신청 목록 + 승인·거절·강제탈퇴 API` (#286) | D-5-2 | Sprint-2 | ✅ PR #342 |
| D-5-4 | `feat(study): PENDING 신청 7일 만료 스케줄러` (#287) | D-5-2 | Sprint-2 | ✅ PR #353 |
| D-5-5 | `feat(study): 가입 시 진행 중 문제집 편입 (upsert)` (#284) | D-5-2 | Sprint-2 | ✅ PR #331 |
| D-5-6 | `fix(study): correlationId 임시 자르기 처리 제거` (#345) | D-5-1, D-5-3 | Sprint-3 | ✅ PR #347 |
| **D-6** | T4-03 스터디 운영 [P1] | D-5 | Sprint-2 | ⬜ 미착수·이월 |
| D-6-1 | `feat(study): 스터디 설정 수정 + 낙관적 락` (#288) | D-5-2 | Sprint-2 | ⬜ |
| D-6-2 | `feat(study): 운영진 임명·해제 + 스터디장 위임 API` (#289) | D-6-1 | Sprint-2 | ⬜ |
| D-6-3 | `feat(study): 스터디 종료 API + admin 강제 종료` (#290) | D-6-2 | Sprint-2 | ⬜ |
| **D-7** | T4-04 문제집·과제 [P1] | D-5 | Sprint-2 | ⚡ (일부 완료) |
| D-7-1 | `feat(study): 문제집 생성 API + SCHEDULED 상태` (#291) | D-5-2 | Sprint-2 | ⬜ |
| D-7-2 | `feat(study): SCHEDULED→OPEN 전이 스케줄러 + 기존 풀이 인정` (#292) | D-7-1 | Sprint-2 | ⬜ |
| D-7-3 | `feat(study): 문제집 목록·상세·진행률 조회 API` (#293) | D-7-2 | Sprint-2 | ⬜ |
| D-7-4 | `feat(study): 완료 유형 판정 및 재채점 재계산` (#294) | D-7-3 | Sprint-2 | ✅ PR #410 |
| D-7-5 | `feat(study): 후속 문제집 후보 조회 API` (#295) | D-7-4 | Sprint-2 | ⬜ |
| D-7-6 | `feat(study): SubmissionJudged 이벤트 수신 → AC 복제본 갱신 + 완료 재계산` (#296) | D-7-5 | Sprint-2 | ⬜ |
| D-7-7 | `fix(study): 문제집 생성 시 권한 재확인 및 race condition 수정` (#374) | D-7-1 | Sprint-3 | ⬜ |
| D-7-8 | `fix(study): 선행 문제집 CLOSE 실패 시 연속 INCOMPLETE 이벤트 누락 수정` (#379) | D-7-7 | Sprint-3 | ⬜ |
| D-7-9 | `feat(study): 배정 회차 번호 저장 및 조회 반환` (#386) | D-7-3 | Sprint-3 | ⬜ |
| D-7-10 | `fix(study): OPEN 중복 편입 시 완료 기록 삽입 생략 복구` (#412) | D-7-2 | Sprint-3 | ✅ PR #413 |
| **D-8** | T4-05 스터디 커뮤니티 [P2] | D-1-3 | Sprint-3 | ⬜ |
| D-8-1 | `feat(study): 게시글·댓글 엔티티·Flyway (V16·V17)` | D-1-3 | Sprint-3 | ⬜ |
| D-8-2 | `feat(study): 게시글 CRUD + 공지·상단고정·숨김 관리 API` | D-8-1 | Sprint-3 | ⬜ |
| D-8-3 | `feat(study): 댓글 CRUD API` | D-8-2 | Sprint-3 | ⬜ |
| **D-9** | T4-06 스터디 추천 [P2] | D-1-5 | Sprint-3 | ⬜ |
| D-9-1 | `feat(study): 추천 Flyway (V18) + LearningProfileUpdated 이벤트 수신` | D-1-5 | Sprint-3 | ⬜ |
| D-9-2 | `feat(study): 규칙 기반 점수 산정 + 추천 결과 저장 서비스` | D-9-1 | Sprint-3 | ⬜ |
| D-9-3 | `feat(study): 추천 조회 API + 콜드 스타트 폴백 + 24h 갱신 스케줄러` | D-9-2 | Sprint-3 | ⬜ |
| **D-10** | T4-07+T4-08 단계별 힌트+가드레일 [P2] ⚠️ 함께 배포 | D-1-5 | Sprint-3 | ⬜ |
| D-10-1 | `feat(study): 힌트 Flyway + 차단 복제본 이벤트 수신` | D-1-5 | Sprint-3 | ⬜ |
| D-10-2 | `feat(study): 힌트 요청 접수 + 일일 횟수 제한 + 중복 멱등 처리` | D-10-1 | Sprint-3 | ⬜ |
| D-10-3 | `feat(study): LLM 비동기 호출 + Redis 캐시 (Spring AI)` | D-10-2 | Sprint-3 | ⬜ |
| D-10-4 | `feat(study): 가드레일 (코드 감지·재생성·폴백·서킷브레이커)` | D-10-3 | Sprint-3 | ⬜ |
| D-10-5 | `feat(study): 시험·대회·진단 차단 판정 fail-closed` | D-10-4 | Sprint-3 | ⬜ |
| D-10-6 | `feat(study): 힌트 이력·단건 조회 API + 기능 토글` | D-10-5 | Sprint-3 | ⬜ |
| **D-11** | T4-09 임베딩 기반 추천 고도화 [P3] | D-9 | Sprint-4 | ⬜ |
| **D-12** | T4-10 사용자 군집화 [P3] | D-9 | Sprint-4 | ⬜ |
| **D-13** | problem 지원 — 태그 존재 확인 내부 API [D 독립 착수·반응형] | - | Sprint-1 | ✅ |
| D-13-1 | `feat(problem): 태그 존재 확인 내부 API 구현` (#237) | - | Sprint-1 | ✅ PR #243 |
| **D-14** | study 지원 — 시험 생성·수정 권한 확인 내부 API [반응형 · C 요청] | - | Sprint-3 | ⚡ |
| D-14-1 | `feat(study): 시험 생성·수정 권한 확인용 내부 API 추가` (#421) | - | Sprint-3 | ⚡ |
