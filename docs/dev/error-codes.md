# 에러 코드 표

> “API 명세서 v1.0 — 전문” 의 에러 코드 표
> 

#### **Common · Auth**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `COMMON_INVALID_REQUEST` | 400 | 요청 파라미터 또는 바디 유효성 검증 실패 |
| `COMMON_INTERNAL_SERVER_ERROR` | 500 | 예상하지 못한 서버 내부 오류 |
| `COMMON_DEPENDENCY_UNAVAILABLE` | 503 | 검증에 필요한 내부 조회 실패 (fail-closed) |
| `IDEMPOTENCY_KEY_CONFLICT` | 422 | 같은 멱등키(또는 식별키)로 다른 본문 요청 |
| `AUTH_TOKEN_INVALID` | 401 | 인증 토큰이 없거나 유효하지 않음 |
| `AUTH_TOKEN_EXPIRED` | 401 | Access Token 만료 |
| `AUTH_REFRESH_TOKEN_INVALID` | 401 | Refresh Token 불일치·폐기 |
| `AUTH_REFRESH_TOKEN_REUSED` | 401 | 이미 교체된 Refresh Token 재사용 (사용자 전체 토큰 폐기) |
| `AUTH_LOGIN_CODE_INVALID` | 401 | 로그인 교환 코드가 없거나 만료됨 |
| `AUTH_OAUTH_FAILED` | 502 | GitHub OAuth 응답 실패 |
| `AUTH_ACCESS_DENIED` | 403 | 해당 리소스에 접근 권한 없음 |

#### **Member · Level · Diagnosis**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `MEMBER_NOT_FOUND` | 404 | 사용자를 찾을 수 없음 |
| `MEMBER_SUSPENDED` | 403 | 정지 회원의 쓰기 요청 |
| `MEMBER_NICKNAME_INVALID` | 400 | 닉네임 형식 위반 |
| `WITHDRAWAL_BLOCKED_ACTIVE_EXAM` | 409 | 실제 입장한 시험·대회가 `FINALIZED` 전 |
| `WITHDRAWAL_BLOCKED_STUDY_LEADER` | 409 | `CLOSED`가 아닌 스터디의 스터디장 |
| `LEVEL_TAG_NOT_FOUND` | 404 | 존재하지 않는 태그 |
| `LEVEL_ALREADY_ENTERED` | 409 | 이미 진입한 태그에 다시 진입 |
| `LEVEL_PROBLEM_LOCKED` | 403 | 태그 레벨보다 높은 난이도의 연습 제출 |
| `DIAGNOSIS_NOT_FOUND` | 404 | 진단 응시를 찾을 수 없거나 본인 응시가 아님 |
| `DIAGNOSIS_NOT_ELIGIBLE` | 409 | 태그 레벨이 `LV3`·`MASTER` |
| `DIAGNOSIS_COOLDOWN` | 409 | 재응시 쿨타임(3일) 중 |
| `DIAGNOSIS_ALREADY_IN_PROGRESS` | 409 | 같은 태그에 진행 중 진단 존재 |
| `DIAGNOSIS_CLOSED` | 409 | 종료된 진단에 저장·제출 |

#### **Problem · Solution**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `PROBLEM_NOT_FOUND` | 404 | 문제를 찾을 수 없음 |
| `PROBLEM_NOT_AVAILABLE` | 404 | `PRIVATE`·`ARCHIVED`·`EXAM_ONLY` 문제에 일반 접근 |
| `PROBLEM_REVISION_NOT_FOUND` | 404 | 문제에 속하지 않는 회차 |
| `PROBLEM_VERSION_CONFLICT` | 409 | `rowVersion` 불일치 (동시 수정) |
| `PROBLEM_INVALID_STATE_TRANSITION` | 409 | 허용되지 않는 공개 상태 전이 |
| `PROBLEM_IN_ACTIVE_EXAM` | 409 | 예정·진행 중 시험·대회에 포함된 문제의 비공개·보관 |
| `PROBLEM_TEST_CASE_INVALID` | 400 | 테스트가 함수 명세와 불일치·중복 |
| `PROBLEM_IMAGE_INVALID` | 400 | 허용되지 않는 이미지 형식·해상도 |
| `PROBLEM_IMAGE_TOO_LARGE` | 413 | 이미지 5MiB 초과 |
| `PROBLEM_TAG_NOT_FOUND` | 404 | 태그를 찾을 수 없음 |
| `PROBLEM_TAG_DUPLICATED` | 409 | 같은 이름의 태그 존재 |
| `PROBLEM_TAG_IN_USE` | 409 | 회차에서 사용 중인 태그 삭제 |
| `SOLUTION_SHARE_NOT_FOUND` | 404 | 공유 풀이를 찾을 수 없거나 열람 권한 없음 |
| `SOLUTION_SHARE_NOT_ALLOWED` | 409 | 본인 `PRACTICE` AC 제출이 아님 |
| `SOLUTION_ALREADY_SHARED` | 409 | 같은 스터디에 같은 제출 공유 중 |
| `SOLUTION_COMMENT_NOT_FOUND` | 404 | 라인 댓글을 찾을 수 없음 |

#### **Submission · Run (judge)**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `UNSUPPORTED_LANGUAGE` | 400 | 지원하지 않거나 비활성 언어 |
| `CODE_TOO_LARGE` | 413 | UTF-8 코드 64KB 초과 |
| `SUBMISSION_RATE_LIMITED` | 429 | 분당 10회 또는 동일 문제 5초 간격 초과 (`Retry-After`) |
| `SUBMISSION_NOT_FOUND` | 404 | 제출을 찾을 수 없거나 본인 제출이 아님 |
| `SUBMISSION_JUDGING_IN_PROGRESS` | 409 | 진행 중 채점 회차가 있는 제출에 재채점·재처리 요청 |
| `SUBMISSION_REJUDGE_NOT_ALLOWED` | 409 | 재채점 대상이 아님 (`PRACTICE`, 정상 판정, 결과 확정 후) |
| `SUBMISSION_RUNTIME_UNAVAILABLE` | 409 | 원래 런타임·실행 템플릿이 없어 재채점 불가 |
| `SUBMISSION_REJUDGE_INPUT_UNAVAILABLE` | 409 | 원본 회차 데이터가 없거나 손상 |
| `RUN_RATE_LIMITED` | 429 | 코드 실행(Run) 요청 한도 초과 |
| `RUN_IN_PROGRESS` | 429 | 같은 회원의 Run이 실행 중 (`run:{memberId}` 락) |
| `RUN_TIMEOUT` | 504 | Run 동기 실행 기한(30초) 초과 |

#### **Exam · Contest**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `EXAM_NOT_FOUND` / `CONTEST_NOT_FOUND` | 404 | 부모 문맥 없음 |
| `EXAM_SUBMISSION_NOT_FOUND` | 404 | 시험 접수 없음 |
| `EXAM_NOT_ELIGIBLE` | 403 | 등록 시 `APPROVED` 구성원 복제본 없음 |
| `EXAM_NOT_REGISTERED` / `CONTEST_NOT_REGISTERED` | 403 | 활성 등록 없음 |
| `EXAM_NOT_STARTED` / `CONTEST_NOT_STARTED` | 403 | 시작 전 입장·문제 조회 |
| `EXAM_CLOSED` / `CONTEST_CLOSED` | 409 | 신규 저장·제출·Run 마감 |
| `EXAM_CANCELED` / `CONTEST_CANCELED` | 409 | 문맥 취소 후 신규 요청 |
| `EXAM_ALREADY_FINISHED` | 409 | 개인 종료 후 새 변경 |
| `EXAM_MODIFICATION_CLOSED` / `CONTEST_MODIFICATION_CLOSED` | 409 | 시작 후 설정 변경·취소 |
| `EXAM_REVISION_CONFLICT` / `CONTEST_REVISION_CONFLICT` | 409 | `version` 충돌 |
| `EXAM_STUDY_CLOSED` | 409 | 스터디 종료 또는 종료 준비 |
| `EXAM_PROBLEM_NOT_AVAILABLE` / `CONTEST_PROBLEM_NOT_AVAILABLE` | 409 | 새 편입할 공개 상태·범위 불일치 |
| `EXAM_PROBLEM_NOT_INCLUDED` / `CONTEST_PROBLEM_NOT_INCLUDED` | 404 | 문맥에 없는 문제 |
| `PROBLEM_REVISION_NOT_INCLUDED` | 404 | 고정 회차와 불일치 |
| `EXAM_PARTICIPATION_CANCELLATION_CLOSED` / `CONTEST_PARTICIPATION_CANCELLATION_CLOSED` | 409 | 시작 후 참가 취소 |
| `EXAM_RESULT_NOT_AVAILABLE` | 409 | `CLOSED` 전 순위 조회 |
| `RESULT_NOT_FINALIZED` | 409 | 결과 미확정 |
| `RESULT_REVISION_NOT_FOUND` | 404 | 없는 확정 버전 |
| `RESULT_CURSOR_EXPIRED` | 409 | 페이지 사이 결과 버전(`resultRevision`) 변경 |
| `RESULT_FINALIZATION_BLOCKED` | 409 | 자동 제출·미종결·관리자 작업 남음 |
| `DRAFT_SEQ_CONFLICT` | 409 | 신규 제출 `seq`가 서버 최신보다 작음 |
| `UNSUPPORTED_EXAM_MODE` | 400 | `FIXED` 외 시험 모드 |
| `SUBMISSION_NOT_SHAREABLE` | 409 | 본인 AC·`FINALIZED` 조건 미충족 |
| `REJUDGE_NOT_ALLOWED` | 409 | `EXAM`·`FAILED`가 아닌 재처리·종결 |
| `CONTEXT_ACCESS_DENIED` | 403 | 내부 문맥 권한 없음 |
| `CONTEXT_NOT_FOUND` | 404 | 내부 문맥 없음 |
| `CONTEST_OPERATION_BLOCKED` / `OPERATION_NOT_CURRENT` / `SOURCE_VERSION_CONFLICT` | 409 | 자원 작업 가드 충돌 (가드 도입 결정 대기) |
| `OPERATION_ALREADY_COMPLETED` | 409 | 완료 작업 취소 요청 (가드 도입 결정 대기) |

#### **Study · Assignment · Hint**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `STUDY_NOT_FOUND` | 404 | 스터디를 찾을 수 없음 |
| `STUDY_NOT_RECRUITING` | 409 | 모집 중(`RECRUITING`)이 아닌 스터디에 가입 신청 |
| `STUDY_FULL` | 409 | 정원 초과 |
| `STUDY_ALREADY_APPLIED` | 409 | 같은 스터디에 `PENDING` 또는 `APPROVED` 가입이 이미 있음 |
| `STUDY_JOIN_LIMIT_EXCEEDED` | 409 | 1인 동시 참여 스터디 5개 상한 초과 |
| `STUDY_MEMBERSHIP_NOT_FOUND` | 404 | 가입 신청·구성원을 찾을 수 없음 |
| `STUDY_MEMBERSHIP_STATUS_CONFLICT` | 409 | 현재 가입 상태에서 허용되지 않는 전이 |
| `STUDY_APPLICATION_EXPIRED` | 409 | 만료 시각이 지난 가입 신청 승인 시도 |
| `STUDY_PENDING_APPLICATIONS_EXIST` | 409 | `APPROVAL → INSTANT` 전환 시 대기 신청 존재 |
| `STUDY_CAPACITY_TOO_SMALL` | 409 | 변경 정원이 현재 구성원 수보다 작음 |
| `STUDY_LEADER_DELEGATION_REQUIRED` | 409 | 스터디장은 위임 후에만 탈퇴 가능 |
| `STUDY_LEADER_ROLE_CHANGE_NOT_ALLOWED` | 409 | 스터디장 역할은 위임 API로만 변경 가능 |
| `STUDY_MANAGER_LIMIT_EXCEEDED` | 409 | 운영진(`MANAGER`) 2명 상한 초과 |
| `STUDY_ALREADY_CLOSED` | 409 | 종료된 스터디에 대한 변경 요청 |
| `STUDY_ACTIVE_EXAM_EXISTS` | 409 | 예정·진행 중 시험이 남아 스터디 종료 불가 (D-1) |
| `STUDY_UPDATE_CONFLICT` | 409 | 스터디 설정 동시 수정 충돌 (`version` 불일치) |
| `STUDY_ASSIGNMENT_NOT_FOUND` | 404 | 문제집을 찾을 수 없음 |
| `STUDY_ASSIGNMENT_PROBLEM_NOT_AVAILABLE` | 409 | 문제집에 넣을 문제가 `GENERAL`·`PUBLIC`이 아님 |
| `STUDY_POST_NOT_FOUND` | 404 | 게시글을 찾을 수 없음 |
| `STUDY_COMMENT_NOT_FOUND` | 404 | 댓글을 찾을 수 없음 |
| `HINT_PROBLEM_NOT_AVAILABLE` | 404 | 힌트 대상이 아닌 문제 (비공개·대회 전용) |
| `HINT_BLOCKED_DURING_EXAM` | 403 | 참가 중인 시험·대회에 포함된 문제 |
| `HINT_BLOCKED_DURING_DIAGNOSIS` | 403 | 진단 진행 중 힌트 요청 (판정 경로 확정 필요) |
| `HINT_LEVEL_SKIPPED` | 409 | 앞 단계 힌트를 받기 전에 다음 단계 요청 |
| `HINT_DAILY_LIMIT_EXCEEDED` | 429 | 일일 힌트 요청 한도(20회) 초과 |
| `HINT_REQUEST_NOT_FOUND` | 404 | 힌트 요청이 없거나 본인 요청이 아님 |

#### **Notification · GitHub**

| 오류 코드 | HTTP | 설명 |
| --- | --- | --- |
| `NOTIFICATION_NOT_FOUND` | 404 | 알림이 없거나 본인 알림이 아님 |
| `GITHUB_NOT_CONNECTED` | 404 | GitHub 연동이 없음 |
| `GITHUB_ALREADY_CONNECTED` | 409 | 이미 연동된 회원 |
| `GITHUB_INSTALLATION_INVALID` | 400 | 설치 ID·`state` 검증 실패 |
| `GITHUB_REPOSITORY_INVALID` | 409 | 설치 대상 저장소가 1개가 아님 |
| `GITHUB_SYNC_NOT_ALLOWED` | 409 | 동기화 대상이 아닌 제출 (`PRACTICE` AC 아님·본인 아님) |

---

end.
