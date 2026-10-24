# 채점 실행 코드 가이드

[정책 및 상태 3.1·3.2](../dev/planning/policy-and-state.md)와 [TypeSpec 공통 타입 명세](../dev/specifications/type-spec.md)를 실행 코드 생성기에 연결하는 방법을 설명한다. 언어별 타입 표현은 TypeSpec 명세에서 관리하며, 이 문서는 생성기 호출·내부 입력·출력 분리·검증 범위를 다룬다.

## 1. 생성기 연결

언어별 생성기는 `judging.service.execution.ExecutionCodeGenerator`를 구현한다.

| 실행 언어 | 생성기 | 함수 호출 방식 |
| --- | --- | --- |
| Java | `JavaExecutionCodeGenerator` | 사용자 소스 뒤에 `Main`을 붙여 `new Solution().solution(...)` 호출 |
| Python 3 | `PythonExecutionCodeGenerator` | 독립 이름 공간에 사용자 코드를 읽고 최상위 동기 `solution(...)` 호출 |
| C++17 | `CppExecutionCodeGenerator` | 공통 타입 선언과 실행기를 제공하고 전역 `solution(...)` 호출 |

1. 호출자는 실행 언어에 맞는 생성기를 선택하고, 고정 문제 회차의 함수 명세를 `FunctionSpec`으로 전달한다. `language()`는 생성기가 지원하는 언어를 반환한다. 인터페이스 자체가 언어별 생성기를 선택하거나 요청을 라우팅하지는 않는다.
2. 기본 코드가 필요하면 `starterCode`를 호출한다. 실행할 때는 같은 함수 명세·사용자 소스·매개변수 이름별 JSON 텍스트인 `argumentTexts`로 `generate`를 호출한다.
3. 공통 결과 `ExecutionSource`는 실행 언어·전체 소스·실행 입력을 담는다. `toRequest`에 고정 실행 환경과 회차의 자원 제한을 전달해 `JudgeClient.create`의 요청으로 연결한다. 최초 실행의 고정 환경은 null이며, 재채점에서는 기록한 실행 환경을 전달한다. 생성 코드와 고정 실행 환경의 언어가 다르면 요청 변환을 거절한다.
4. 생성 응답의 token으로 같은 실행 결과를 조회한다. 반환값 비교와 최종 판정은 상위 실행기에서 처리한다.

생성기·타입 매퍼는 `service/execution`의 언어별 하위 패키지에 둔다. `ExecutionSource`·`JudgeRuntime`·`JudgeExecutionCreateResponse`·`JudgeExecutionResultResponse`는 `service/execution/dto`에 둔다. 언어 목록 조회 결과인 `JudgeLanguageAvailability`는 `service`에 둔다.

각 생성기는 함수명·매개변수 구조·TypeSpec을 확인하고 해당 언어의 예약어를 매개변수 이름에서 거절한다. Java는 예약어·리터럴·단일 밑줄을 거절하며, 매개변수 위치에서 유효한 문맥 키워드는 허용한다. Python은 최상위 동기 함수를 요구하고, C++은 실행기와 충돌하는 매개변수 이름도 거절하며, 숫자 입력·반환 타입을 `int`·`long long`·`float`·`double`에 정확히 맞춘다. 생성한 기본 코드와 실행 코드는 이 기본형의 비트 폭·표현 범위를 정적으로 검사한다. 사용자 소스의 구문과 호출 시그니처는 실제 컴파일·실행 결과로 확인한다.

## 2. 내부 입력 변환

입력 원천은 `argumentTexts`의 JSON 텍스트다. 입력 변환기는 매개변수 선언 순서대로 타입·숫자 범위·배열 구조·객체 필드를 검증하고, 타입 태그가 포함된 Base64 실행 스트림을 생성한다. 사용자 입력값을 실행 소스의 문자열 상수로 삽입하지 않는다. 이 내부 스트림은 외부 API나 S3 테스트 파일 형식이 아니다.

Java는 `JavaInputEncoder`, Python·C++은 `TypedExecutionInputEncoder`를 사용한다. Java 입력에는 배열 복원용 클래스 정보가 포함되므로 두 인코더의 스트림을 서로 바꾸어 사용하지 않는다. 각 언어의 실행 템플릿은 대응하는 스트림을 읽어 해당 언어의 값으로 복원한다.

입력 변환은 `FLOAT32`·`FLOAT64`를 각각 binary32·binary64 값으로 변환한다. 무한대가 되는 범위 초과와 0이 아닌 값이 0으로 소실되는 언더플로를 거절하며, 실수의 음수 0은 보존한다. Python에서는 binary32로 변환한 입력도 Python `float`로 복원한다. 이 검사는 실행 입력의 복원 단계이며 기대값 비교를 수행하지 않는다. 상위 비교기는 [TypeSpec 명세의 선언 타입 정규화와 정확 비교](../dev/specifications/type-spec.md#7-선언-타입-정규화와-정확-비교)를 따른다.

## 3. 실행 결과 매핑

반환값은 Judge0의 `stdout`에 JSON으로 기록하고, 디버그 출력은 Judge0의 `stderr`로 분리한다. 외부 응답 필드의 계약은 [API 명세서 > 코드 실행](../dev/specifications/api-spec.md#318-코드-실행-run)을 따른다.

| 실행 언어 | 반환값 | 디버그 출력 분리 |
| --- | --- | --- |
| Java | `solution` 반환값을 JSON으로 직렬화 | `System.out`을 `System.err`로 전환 |
| Python | `solution` 반환값을 JSON으로 직렬화 | 사용자 코드를 읽기 전에 `sys.stdout`을 `sys.stderr`로 전환하여 `print` 출력 분리 |
| C++ | `solution` 반환값을 JSON으로 직렬화 | POSIX 파일 디스크립터를 전환하여 `std::cout`·`printf`·전역 초기화 출력 분리 |

직접 작성한 표준 오류 출력도 내부 디버그 스트림에 함께 들어간다. 상위 실행기는 Judge0의 원시 `stdout`을 API의 `actualReturnText`로, 디버그 출력인 원시 `stderr`를 API의 `stdout`으로 구분해 매핑한다. 예외 스택 등 오류 정보의 노출은 공개 API 정책을 따른다.

NaN·Infinity나 지원하지 않는 객체처럼 JSON으로 직렬화할 수 없는 반환값은 실행 실패로 남긴다. JSON 직렬화가 성공했더라도 선언한 TypeSpec과 기대값을 만족하는지는 상위 비교기가 확인한다. 출력량 제한은 회차의 `outputLimitKb`를 상위 실행기에 연결할 때 적용한다.

## 4. 검증 범위

| 테스트 | 검증 범위 |
| --- | --- |
| `ExecutionSourceTest` | 실행 언어·소스·입력·자원 제한 보존, 다른 언어의 고정 환경 연결 거절 |
| `ExecutionCodeGeneratorTest` | 공통 인터페이스로 기본 코드·실행 코드 생성, 지원 언어 일치, 잘못된 명세 거절 |
| 언어별 `ExecutionCodeGeneratorTest` | 기본 코드·함수 명세·입력 변환·예약어·숫자 범위 오류 |
| `JavaExecutionProcessTest` | 생성 코드를 `--release 17`로 컴파일하고 테스트 JDK의 자식 JVM에서 실행 |
| `PythonExecutionProcessTest` | 로컬 `python3 -I` 자식 프로세스에서 생성 코드 실행 |
| `CppExecutionProcessTest` | 로컬 `c++ -std=c++17 -O0`로 생성 코드 실행, 기본형 숫자·`Value` 복원과 다른 숫자 시그니처 거절 검증 |

프로세스 테스트는 기본 타입·중첩 배열·객체·혼합 배열, 숫자 경계와 디버그 출력 분리, 컴파일·구문·실행 실패를 검증한다. 테스트 실행 환경에는 JDK와 해당 언어의 실행기·컴파일러가 필요하다. C++ 실행기는 POSIX 기능을 사용한다.

위 테스트만으로 배포 Judge0의 런타임 버전·컴파일러·자원 제한을 확인한 것으로 간주하지 않는다. [운영 문서의 Judge0 실제 연동 테스트](../operations/env-example.md#5-judge0-채점-엔진-가동-및-확인)는 실행 접수·조회와 배포 런타임을 확인하며, 생성기의 모든 시나리오를 실제 Judge0에서 검증하는 테스트는 아니다.
