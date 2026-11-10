# 채점 골든셋

채점 파이프라인 회귀 테스트용 더미 문제와 코드 세트.

각 문제 폴더에는 `spec.json`(FunctionSpec), `testcases.json`(입력·기댓값), 그리고 판정 유형별 `class Solution` 코드가 있다.

| 폴더 | 문제 | 반환 타입 |
| --- | --- | --- |
| `p1-sum` | 두 정수의 합 | `int` |
| `p2-array-max` | 배열 최대값 | `int` |
| `p3-palindrome` | 팰린드롬 확인 | `boolean` |

## 코드 파일 규칙

| 파일 | 판정 | 설명 |
| --- | --- | --- |
| `ac.java` | AC | 정답 |
| `wa.java` | WA | 오답 (틀린 값 반환) |
| `tle.java` | TLE | 시간 초과 (무한 루프·극단적 복잡도) |
| `re.java` | RE | 실행 오류 (예외 발생) |
| `ce.java` | CE | 컴파일 오류 (타입 불일치) |

각 파일은 `JavaExecutionCodeGenerator`가 그대로 받을 수 있는 `class Solution` 단독 코드다. 패키지 선언 없음.


## Problem 진단 기준 데이터 등록

Problem·Judge 메시징과 Java 채점 실행기가 연결된 테스트 환경에서 기존 태그 하나에 난이도별 기준 문제를 등록할 수 있다. 저장소 루트에서 아래 명령을 실행하고, 해당 환경의 관리자 액세스 토큰을 입력한다.

```bash
python3 problem-service/scripts/seed-diagnosis-problems.py http://localhost:8080 <태그-ID>
```

첫 번째 테스트는 공개 예제로, 나머지는 숨김 테스트로 등록한다. 오답 코드에 `WA`가 있는지, 정답 코드의 모든 테스트가 `AC`인지와 Problem의 결과 반영 여부를 확인한 후 그 회차를 공개한다. 같은 환경에서 같은 관리자와 태그로 재실행하면 등록·검증 멱등키를 재사용한다.
