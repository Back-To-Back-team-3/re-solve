# TypeSpec 공통 타입 명세

API 명세서 2.3.0의 `functionSpec.parameters[].type`과 `returnType`에 사용하는 값 하나의 타입 명세다. 실제 입력·기대값은 `argumentTexts`·`expectedReturnText`의 JSON 텍스트로 전달한다.

기존 [Notion TypeSpec 공통 타입 명세](https://app.notion.com/p/3e9c4b3d7d9e8142b28fca126f70209f)의 계약을 저장소에서 참조할 수 있도록 정리한다. 언어별 타입 표현은 이 문서에서 정의하며, 변환 구현 방법과 검증 범위는 [채점 실행 코드 가이드](../../guides/judge-execution.md)에 둔다.

## 1. 필드와 지원 타입

| 필드 | 타입 | 필수 조건 | 설명 |
| --- | --- | --- | --- |
| `kind` | string | 항상 | `INT32` / `INT64` / `FLOAT32` / `FLOAT64` / `STRING` / `BOOLEAN` / `ARRAY` / `OBJECT` |
| `elementType` | TypeSpec | `ARRAY`의 세 방식 중 하나 | 모든 원소에 적용할 타입. 길이는 가변 |
| `positionTypes` | TypeSpec[] | `ARRAY`의 세 방식 중 하나 | 인덱스별 타입. 실제 배열 길이는 목록 길이와 동일 |
| `repeatTypes` | TypeSpec[] | `ARRAY`의 세 방식 중 하나 | 비어 있지 않은 반복 패턴. 실제 배열 길이는 패턴 길이의 배수 |
| `properties` | object (이름 → TypeSpec) | `OBJECT` | 고정 필드 이름과 타입. 선언한 필드는 모두 필수 |

| kind | 의미 | 추가 필드 |
| --- | --- | --- |
| `INT32` | 부호 있는 32비트 정수 | 없음 |
| `INT64` | 부호 있는 64비트 정수 | 없음 |
| `FLOAT32` | 32비트 부동소수점 | 없음 |
| `FLOAT64` | 64비트 부동소수점 | 없음 |
| `STRING` | 문자열 | 없음 |
| `BOOLEAN` | 불리언 | 없음 |
| `ARRAY` | 순서를 보존하는 배열 | 세 방식 중 정확히 하나 |
| `OBJECT` | 추가·누락 필드를 허용하지 않는 고정 필드 객체 | `properties` |

기본 타입은 `kind`만 지정한다. 배열 원소와 객체 필드에는 TypeSpec을 재귀적으로 중첩할 수 있다. 기본 타입에 중첩 필드를 지정하거나 `OBJECT`에 배열용 필드를 지정하면 명세 오류다.

## 2. 배열

`elementType`·`positionTypes`·`repeatTypes` 중 정확히 하나를 지정한다. 둘 이상을 지정하거나 전부 생략하거나 선택한 필드를 null로 보내면 명세 오류다. 별도 `TUPLE`이나 원소별 허용 타입 집합은 두지 않는다.

| 방식 | 예시 명세 | 허용 값 예시 | 거절 값 예시 |
| --- | --- | --- | --- |
| 동일 타입 | `{"kind":"ARRAY","elementType":{"kind":"INT32"}}` | `[]`, `[1,2]` | `[1,"2"]` |
| 위치별 타입 | `{"kind":"ARRAY","positionTypes":[{"kind":"INT32"},{"kind":"STRING"}]}` | `[1,"a"]` | `["a",1]`, `[1]` |
| 반복 패턴 | `{"kind":"ARRAY","repeatTypes":[{"kind":"INT32"},{"kind":"STRING"}]}` | `[]`, `[1,"a",2,"b"]` | `[1,"a",2]` |

- `positionTypes`의 빈 목록은 빈 배열만 허용한다.
- `repeatTypes` 명세 자체는 빈 목록을 허용하지 않는다. 패턴 길이가 `k`이면 길이는 `k`의 배수이고, 인덱스 `i`는 `repeatTypes[i % k]`로 검증한다. 실제 빈 배열은 패턴 0회로 허용한다.
- 입력·반환값에 같은 규칙을 적용한다. 정렬하거나 묶음으로 재구성하지 않고 길이·순서·값을 재귀적으로 검증·비교한다.

## 3. 객체와 값 검증

- `OBJECT`는 `properties`의 키 집합과 실제 객체의 키 집합이 정확히 같아야 한다. 빈 객체는 빈 `properties`에서만 허용한다.
- null·NaN·Infinity·중복 객체 키는 허용하지 않는다. 빈 문자열·배열은 허용한다.
- 숫자·문자열·불리언을 자동 변환하지 않는다. 정수는 선언한 비트 범위 안에 있어야 한다.
- 문자열의 공백·대소문자·줄바꿈과 배열 순서를 그대로 비교한다. 객체 키 순서는 비교하지 않는다.
- 소수는 허용 오차 없이 숫자 값으로 비교한다. 문제별 `comparatorPolicy`는 두지 않는다.
- 관리자 입력·기대값의 타입·구조 불일치는 422로 거절한다. 정상 실행한 사용자 반환값의 타입·값 불일치는 `WA`다. 실행 실패의 판정은 정책 및 상태 3.3을 따른다.

예를 들어 `values`라는 배열 인자 하나를 받는 함수의 입력은 `{"values":"[1,\"a\"]"}`로 전달한다. 인자 목록을 표현하는 바깥 배열을 추가하지 않는다. `expectedReturnText`도 반환값 하나의 JSON 텍스트다.

## 4. Java 타입 매핑

Java 함수의 매개변수·반환 타입은 아래 표현을 사용한다. 클래스 형태와 함수 선언 조건은 [정책 및 상태 3.1 제출](../planning/policy-and-state.md#31-제출)을 따른다.

| TypeSpec | Java 타입 |
| --- | --- |
| `INT32` | `int` |
| `INT64` | `long` |
| `FLOAT32` | `float` |
| `FLOAT64` | `double` |
| `STRING` | `String` |
| `BOOLEAN` | `boolean` |
| `OBJECT` | `java.util.Map<String, Object>` |
| `ARRAY` + `elementType` | 원소 타입의 배열. 예: `int[]`, `String[]`, `int[][]` |
| `ARRAY` + `positionTypes` 또는 `repeatTypes` | `Object[]`. 위치별 타입의 값이 들어가며 숫자는 대응하는 래퍼 타입으로 보관 |

객체 필드와 혼합 배열의 숫자는 선언 타입에 따라 `Integer`·`Long`·`Float`·`Double`로 복원한다. 배열과 객체의 중첩·필드 집합·길이·순서는 공통 타입 명세로 검증한다. `List`를 배열 반환 타입 대신 사용하거나 임의 DTO를 객체 반환 타입으로 사용하는 계약은 제공하지 않는다.

## 5. 수치 비교의 협의 필요 사항

`FLOAT32`의 반올림된 실행값과 원본 기대 JSON을 정확 비교할 때 적용할 정규화 규칙은 언어 공통 비교기 구현 전에 협의해야 한다. 허용 오차를 도입하거나 기대값을 임의로 반올림한 것으로 간주하지 않는다. 현재 Java 입력 변환의 범위 검사와 음수 0 처리는 [실행 코드 가이드](../../guides/judge-execution.md)에 설명한다.
