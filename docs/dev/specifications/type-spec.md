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
- 부동소수 값은 [선언 타입 정규화와 정확 비교](#7-선언-타입-정규화와-정확-비교)에 따라 비교한다. 문제별 `comparatorPolicy`는 두지 않는다.
- 함수 명세 오류는 `COMMON_INVALID_REQUEST`(400), 관리자 입력·기대값의 타입·구조 불일치는 `PROBLEM_TEST_CASE_INVALID`(400)로 거절한다. 정상 실행한 사용자 반환값의 타입·값 불일치는 `WA`다. 실행 실패의 판정은 정책 및 상태 3.3을 따른다.

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

## 5. Python 타입 매핑

Python 함수는 최상위 동기 `solution`으로 작성하며, 기본 코드는 아래 표의 표준 타입 힌트를 사용한다. 타입 힌트는 값의 비트 범위나 중첩 구조를 런타임에서 강제하지 않으므로 실행 입력 변환기와 상위 반환값 비교기가 TypeSpec을 기준으로 검증한다.

| TypeSpec | Python 타입 |
| --- | --- |
| `INT32` / `INT64` | `int` |
| `FLOAT32` / `FLOAT64` | `float` |
| `STRING` | `str` |
| `BOOLEAN` | `bool` |
| `ARRAY`의 세 방식 | `list` |
| `OBJECT` | 문자열 키의 `dict` |

Python `int`가 큰 정수를 표현할 수 있어도 입력은 선언한 `INT32`·`INT64` 범위 안에 있어야 한다. `FLOAT32` 입력은 binary32로 변환한 값을 Python `float`에 복원한다. Python 연산 자체가 binary32 정밀도로 고정되는 것은 아니다. 반환값의 타입·범위와 숫자 비교는 3절·7절의 공통 계약을 따른다.

배열을 `tuple`이나 `set`으로 자동 변환하지 않으며, 객체는 문자열 키의 `dict`로 표현한다. 동일 타입·위치별 타입·반복 패턴의 길이·순서·원소 타입과 객체의 필드 집합은 공통 TypeSpec으로 검증한다.

## 6. C++ 타입 매핑

C++은 C++17 표준 타입과 생성기가 제공하는 `Value`를 사용한다. 사용자 함수는 전역 `solution`으로 작성하며, 기본 코드에 포함된 공통 타입 선언을 사용한다. 실행기와 직렬화기는 외부 JSON 라이브러리를 요구하지 않는다.

| TypeSpec | C++ 타입 |
| --- | --- |
| `INT32` | `int` |
| `INT64` | `long long` |
| `FLOAT32` | `float` |
| `FLOAT64` | `double` |
| `STRING` | `std::string` |
| `BOOLEAN` | `bool` |
| `OBJECT` | `std::map<std::string, Value>` |
| `ARRAY` + `elementType` | `std::vector<T>`. 예: `std::vector<int>`, `std::vector<std::vector<std::string>>` |
| `ARRAY` + `positionTypes` 또는 `repeatTypes` | `std::vector<Value>` |

`Value`의 `data`는 다음 선언 타입을 보관하는 `std::variant`다.

```cpp
using Array = std::vector<Value>;
using Object = std::map<std::string, Value>;
using Storage = std::variant<
    int, long long, float, double,
    std::string, bool, Array, Object
>;
```

혼합 배열의 원소와 객체 필드는 선언 TypeSpec에 해당하는 대안으로 복원한다. 예를 들어 `INT32` 값은 `std::get<int>(value.data)`로 읽는다. 중첩 배열은 `Value::Array`, 중첩 객체는 `Value::Object`로 표현한다. 배열의 길이·순서·반복 패턴과 객체의 필드 집합은 공통 계약을 따르며, `std::map`의 순서는 객체 키의 비교 기준이 아니다.

숫자는 사용자 함수·배열 원소·`Value::Storage`에서 동일하게 `int`·`long long`·`float`·`double`로 표현한다. `INT64`에는 플랫폼에 따라 크기가 달라지는 `long`을 사용하지 않는다. 기본형의 이름만으로 정확한 비트 폭이 보장되지는 않으므로, 생성기는 아래 조건을 `static_assert`로 검사한다. 조건에 맞지 않는 환경에서는 기본 코드와 실행 코드의 컴파일을 거절한다.

| 대상 | 정적 검사 조건 |
| --- | --- |
| 바이트 | `CHAR_BIT == 8` |
| `int` | 4바이트, 부호를 제외한 값 비트 31개, 최솟값 −2³¹ |
| `long long` | 8바이트, 부호를 제외한 값 비트 63개, 최솟값 −2⁶³ |
| `float` | 4바이트, IEEE 754 지원, 기수 2, 유효숫자 24비트, `min_exponent == -125`, `max_exponent == 128` |
| `double` | 8바이트, IEEE 754 지원, 기수 2, 유효숫자 53비트, `min_exponent == -1021`, `max_exponent == 1024` |

함수 시그니처도 입력·반환값의 기본형이 위 매핑과 정확히 같아야 한다. 매개변수의 참조·`const`는 허용하지만 암묵적 수치 변환은 허용하지 않는다. 예를 들어 `INT64` 함수에 `long`을 쓰면 `long`과 `long long`이 같은 크기인 환경에서도 거절한다. 내부 실행 스트림의 길이·비트열을 읽는 unsigned 정수는 바이트 해석용 고정 폭 타입으로 유지한다.

생성기는 표준 타입 이름 앞에 전역 한정자 `::`를 붙인다. `::std::vector<...>`와 `std::vector<...>`는 같은 표준 타입을 가리킨다. 정상 실행한 반환값의 TypeSpec·범위·기대값 일치 여부는 상위 비교기가 확인한다.

## 7. 선언 타입 정규화와 정확 비교

기대값과 실제 반환값을 각각 TypeSpec의 선언 타입으로 정규화한 뒤, 허용 오차 없이 숫자 값이 같은지 비교한다. 원본 JSON 텍스트의 자릿수·표기 방식이나 두 값의 차이가 임의의 epsilon보다 작은지는 비교 기준으로 사용하지 않는다.

1. 먼저 값의 JSON 타입·구조·표현 범위를 검증한다. 숫자 문자열·불리언을 숫자로 변환하지 않는다. 정수 타입은 정수 값과 선언한 비트 범위를 그대로 유지하며 부동소수 변환을 거치지 않는다.
2. `FLOAT32`는 기대값과 실제 반환값을 각각 IEEE 754 binary32로, `FLOAT64`는 각각 binary64로 직접 변환한다. 가장 가까운 표현값으로 반올림하고 정확히 중간이면 최하위 비트가 짝수인 값을 선택한다. 특히 `FLOAT32`는 중간 binary64 변환에 따른 이중 반올림을 피한다.
3. NaN·Infinity와 변환 후 무한대가 되는 범위 초과는 허용하지 않는다. 0이 아닌 입력이 0으로 소실되는 언더플로도 허용하지 않는다. 유한한 비정규화 수는 허용한다.
4. 정규화한 두 값이 수치적으로 같으면 일치다. 추가 허용 오차는 두지 않으며 `-0.0`과 `+0.0`은 같은 숫자 값으로 비교한다. 실행 입력에서 0의 부호를 보존하는 것과 판정에서 두 0을 같게 비교하는 것은 구분한다.
5. 배열의 각 위치와 객체의 각 필드에도 해당 위치의 TypeSpec을 따라 같은 규칙을 재귀 적용한다. 정규화로 배열 순서·길이나 객체 키 집합을 바꾸지 않는다.

| 선언 타입 | 기대 JSON | 실제 반환 JSON | 정규화 결과와 비교 |
| --- | --- | --- | --- |
| `FLOAT32` | `16777217` | `16777216` | 모두 `16777216`이므로 일치 |
| `FLOAT32` | `16777217` | `16777218` | 각각 `16777216`·`16777218`이므로 불일치 |
| `FLOAT32` | `0.1` | `0.10000000149011612` | 같은 binary32 값이므로 일치 |
| `FLOAT32` | `-0.0` | `0.0` | 같은 숫자 값으로 일치 |
| `INT64` | `9007199254740993` | `9007199254740992` | 정수를 반올림하지 않으므로 불일치 |

정상 실행된 반환값의 타입·범위 불일치 또는 정규화 후 값 불일치는 `WA`다. 관리자 입력·기대값의 검증 실패와 실행 실패는 3절 및 정책 및 상태 3.3의 구분을 유지한다.

이 절은 상위 채점 비교기의 구현 계약이다. 실행 코드 생성기는 입력을 변환하고 실제 반환값을 직렬화하며 기대값 비교는 수행하지 않는다. 생성된 반환값의 비교는 `JudgeReturnComparator`, 실행 상태·자원·출력 제한을 포함한 테스트 판정은 `JudgeResultEvaluator`가 담당한다. 연결 방법과 검증 범위는 [실행 코드 가이드](../../guides/judge-execution.md)를 따른다.
