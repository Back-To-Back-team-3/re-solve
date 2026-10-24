# 채점 실행 코드 가이드

[정책 및 상태 3.1·3.2](../dev/planning/policy-and-state.md)와 [TypeSpec 공통 타입 명세](../dev/specifications/type-spec.md)를 구현에서 연결하는 방법을 설명한다. 사용자 코드의 클래스·함수 선언 조건은 정책 문서에, Java 타입 매핑은 타입 명세에 둔다.

## 1. Java 생성기 연결

1. 고정 문제 회차에서 읽은 함수 명세를 `FunctionSpec`으로 전달한다. 입력 원천은 매개변수 이름별 JSON 텍스트인 `argumentTexts`다.
2. 기본 코드가 필요하면 `JavaExecutionCodeGenerator.starterCode`를 호출한다. 실행 시에는 같은 함수 명세·사용자 소스·입력으로 `generate`를 호출한다.
3. 생성 결과 `JavaExecutionSource`는 전체 소스와 실행 입력을 담는다. `toRequest`에 고정 실행 환경과 회차의 자원 제한을 전달해 `JudgeClient.create`의 요청으로 연결한다. 최초 실행의 고정 환경은 null이며, 재채점에서는 기록한 실행 환경을 전달한다.
4. 생성 응답의 token으로 같은 실행 결과를 조회한다. 반환값 비교와 최종 판정은 상위 실행기에서 처리한다.

생성기는 같은 소스에 `Main`을 덧붙이고 `new Solution().solution(...)`을 호출한다. 클래스·함수 선언과 타입 표현은 위 명세를 참조한다. 생성기 자체는 함수명·매개변수 구조·TypeSpec을 확인하며, 사용자 소스의 구문·접근 제한·메서드 시그니처는 실제 컴파일 결과로 확인한다.

## 2. 내부 입력 변환

입력 원천은 `argumentTexts`의 JSON 텍스트다. Java 입력 변환기는 이를 매개변수 선언 순서대로 검증한 뒤 타입 태그가 포함된 Base64 실행 스트림으로 바꾸고, 생성된 `Main`이 같은 형식으로 복원한다. 이 내부 스트림은 외부 API나 S3 테스트 파일 형식이 아니다.

현재 Java 입력 변환은 `FLOAT32`·`FLOAT64`를 각각 `float`·`double`로 변환한다. 무한대가 되는 범위 초과와 0이 아닌 값이 0으로 소실되는 언더플로를 거절하며, 실수의 음수 0은 보존한다. 이 검사는 실행 언어로 입력을 복원하는 단계이며 최종 반환값 비교를 수행하지 않는다. 공통 비교기의 수치 정규화 미결정 사항은 [TypeSpec 명세](../dev/specifications/type-spec.md#5-수치-비교의-협의-필요-사항)에 둔다.

## 3. 실행 결과 매핑

정책의 반환값·디버그 출력 분리 원칙을 아래와 같이 연결한다. 외부 응답 필드의 계약은 [API 명세서 > 코드 실행](../dev/specifications/api-spec.md#318-코드-실행-run)을 따른다.

| 경계 | 반환값 | 디버그 출력 |
| --- | --- | --- |
| Java 생성 코드 → Judge0 | Judge0의 `stdout`에 JSON 반환값 기록 | `System.out`을 `System.err`로 바꿔 Judge0의 `stderr`에 기록 |
| 외부 Run API | `actualReturnText` | API의 `stdout` |

직접 작성한 `System.err` 출력도 내부 디버그 스트림에 함께 들어간다. 따라서 상위 실행기는 Judge0의 원시 `stdout`·`stderr`를 API 필드로 그대로 복사하지 않고, 반환값과 디버그 출력을 구분해 매핑해야 한다. 예외 스택 등 오류 정보의 노출은 공개 API 정책을 따른다.

반환값은 JSON으로 직렬화하고, NaN·Infinity처럼 JSON으로 표현할 수 없는 반환값은 실행 실패로 남긴다. 정상적으로 직렬화된 반환값의 TypeSpec·기대값 비교는 상위 실행기의 책임이다. 출력량 제한은 회차의 `outputLimitKb`를 상위 실행기에 연결할 때 함께 적용해야 한다.

## 4. 검증 범위

- `JavaExecutionCodeGeneratorTest`는 기본 코드·함수 명세·입력 변환과 범위 오류를 검증한다.
- `JavaExecutionProcessTest`는 생성 코드를 `--release 17`로 컴파일하고 테스트 JDK의 자식 JVM에서 실행한다. 64비트 정수, 부동소수 입력 경계, 중첩 배열·객체·혼합 배열, 디버그 출력 분리, 컴파일·실행 실패를 검증한다.
- 위 테스트만으로 배포 Judge0의 Java 17 지원이나 실제 자원 제한을 확인한 것으로 간주하지 않는다.
- [운영 문서의 Judge0 실제 연동 테스트](../operations/env-example.md#5-judge0-채점-엔진-가동-및-확인)는 실행 접수·조회와 배포 런타임을 확인한다. 함수 실행 코드 생성기의 모든 시나리오를 실제 Judge0에서 검증하는 테스트는 아니다.
