# Spotless & Code 컨벤션 & Build 메뉴얼

## 1. 코드 컨벤션 개요

- Naver Java Coding Convention v1.2 기반. 들여쓰기는 **스페이스 4칸**, 최대 줄 길이 120자.
    - **포맷 적용 (SSOT)**: Spotless + `config/naver-eclipse-formatter.xml`
    - **구조·네이밍 검증**: Checkstyle + `config/naver-checkstyle-rules.xml` (+ `config/naver-checkstyle-suppressions.xml`)
- IntelliJ 포맷은 근사치이며, **최종 판정은 Spotless가 한다**.
- 커밋 전 `./gradlew spotlessApply` 실행 필수.
- 들여쓰기 검사는 Checkstyle에서 제외.
    - `naver-checkstyle-suppressions.xml`에 Indentation 모듈 예외 규칙이 추가되어, 들여쓰기 판정은 전적으로 Spotless가 담당한다.
    - Checkstyle 리포트에 Indentation 위반이 나타나지 않는 것이 정상이다.

---

## 2. IntelliI 사전 설정

- Naver Java Formatter 적용
    - IntelliJ > Settings > Editor > Coding Style > Java > [Scheme: ]  톱니바퀴 > Import Scheme > [IntelliJ IDEA code style XML] > `re-solve/config/naver-intellij-formatter.xml` > Apply
- 저장 시 자동 정리
    - IntelliJ > Settings > Tools > 기능 및 저장(Actions on Save) > [Reformat code], [Import 문 최적화(Optimize imports)] 체크
- CheckStyle-IDEA 플러그인 설치
    - IntelliJ > Settings > Plugins > [CheckStyle-IDEA] 설치 >IDE 재시작
- CheckStyle 적용
    - `Checkstyle version`을 `build.gradle`의 `toolVersion`에 가급적 맞춘다.
    - IntelliJ > Settings > Tools > Checkstyle > Configuration File `+` 추가 >
        - Description: Naver Coding Convention
        - File: `re-solve/config/naver-checkstyle-rules.xml`
        - > Next > 프로퍼티 `suppressionFile`, Value: `config/naver-checkstyle-suppressions.xml` ] 지정
        - > Next > Apply

---

## 3. 신규 도메인 / 대규모 작업시 — Code 컨벤션 적용 절

```bash
### 0. 상태 확인
git status                          # 작업 중인 변경사항 없는지 확인
git switch develop
git pull origin develop

### 1. 브랜치 생성
git switch -c style/$ISSUE-$DOMAIN-code-convention
git push -u origin style/$ISSUE-$DOMAIN-code-convention  

### 2. 포매팅 적용 및 범위 정리
./gradlew spotlessApply

git status                          # 전체 도메인이 바뀐 것 확인
git add $DOMAIN-service/src/main/java/com/backtoback/$DOMAIN/
git add $DOMAIN-service/src/test/java/com/backtoback/$DOMAIN/

git restore .                       # 스테이징 안 된 나머지 도메인 되돌리기
git status                          # $DOMAIN-service만 남았는지 재확인

### 3. 최종 점검 후 1차 커밋
./gradlew spotlessCheck
./gradlew build -x test

git commit -m "style($DOMAIN): $DOMAIN 도메인 코드 컨벤션 및 자동 포매팅 적용"
git push -u origin style/$ISSUE-$DOMAIN-code-convention

### 4. Checkstyle 위반 확인
./gradlew checkstyleMain checkstyleTest
# HTML 리포트 확인
# start build/reports/checkstyle/main.html
# start build/reports/checkstyle/test.html

### 5. 수동 수정 후 2차 커밋
./gradlew spotlessApply                      # 수동 수정으로 흐트러진 포맷 재정렬
./gradlew checkstyleMain checkstyleTest      # 위반 0 확인

git status                                   # 담당 도메인 밖 수정 여부 먼저 확인

git add $DOMAIN-service/src/main/java/com/backtoback/$DOMAIN/
git add $DOMAIN-service/src/test/java/com/backtoback/$DOMAIN/
git restore .
git status

./gradlew spotlessCheck
./gradlew build -x test

git commit -m "style($DOMAIN): 네이밍 및 import 순서 컨벤션 정리"
git push -u origin style/$ISSUE-$DOMAIN-code-convention

### 6. 최종 점검
./gradlew spotlessCheck             # 포맷 통과 확인
./gradlew build -x test             # 컴파일 및 전체 check 통과 확인
git log --oneline -5                # 커밋 2개(포맷팅 / 네이밍정리)로 분리됐는지 확인
```

**자주 나오는 위반 유형**

| 규칙 | 의미 | 수정 예시 |
| --- | --- | --- |
| `[import-grouping]` | import 그룹(`#`(static) → `java` → `javax` → `org` → `net` → `com` → 그 외 → `com.nhncorp` → `com.navercorp` → `com.naver`) 순서·빈 줄 위반 | 그룹별로 재정렬 + 그룹 사이 빈 줄 |
| `[avoid-star-import]` | `import xxx.*` 형태 금지 | 실제 쓰는 이름으로 개별 import |
| `[need-braces]` | 한 줄 `if`에 중괄호 누락 | `if (x) y;` → `if (x) { y; }` |
| `[var-lower-camelcase]` / `[avoid-1-char-var]` | 1글자 변수명 금지 | `s` → `memberId` (파라미터명과 겹치지 않게 주의) |
| `[space-around-brace]` | 빈 중괄호 앞뒤 공백 누락 | `{}` → `{ }` (줄바꿈) |
| `[line-length-120]` | 120자 초과 | 문자열 `+` 연결로 줄바꿈 |
| `[braces-knr-style]` | 닫는 중괄호가 단독 줄에 있지 않음 | K&R 스타일로 정리 | 

- `[space-around-brace]`
    - `{}` → 한 칸 공백(`{ }`)은 Spotless가 다시 축소시켜 재발한다.
    - 아래처럼 줄바꿈해서 빈 몸통으로 만들어야 통과한다.
        ```bash
        public interface SomeRepository extends JpaRepository<SomeEntity, Long> {
              
        }
        ```

**충돌 대비**

```bash
git fetch origin
git rebase origin/develop
```

---

## 4. 평소 개발 시 — 매 커밋 전 루틴

**작업 중 반복 확인용**

```bash
./gradlew spotlessApply
./gradlew compileJava
./gradlew compileTestJava
```

**신규 파일을 만들었거나 기존 파일을 크게 고쳤을 때** (선택이지만 권장)

```bash
./gradlew checkstyleMain checkstyleTest
```

**커밋/PR 올리기 전 최종 확인용 (필수)**

```bash
./gradlew spotlessCheck
./gradlew clean test
```

**CI에서 포맷 검사가 실패한 경우**

```bash
./gradlew spotlessApply
git add -A
git commit -m "style($DOMAIN): spotlessApply 적용"
git push origin <branch>
```

---

end.
