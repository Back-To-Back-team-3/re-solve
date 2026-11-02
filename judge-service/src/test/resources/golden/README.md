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
