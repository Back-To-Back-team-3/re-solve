<div align="center">
  <h1>🧩 Re:Solve</h1>
  <p><strong>개인 문제 풀이·스터디·대회·모의 코딩 테스트를 한곳에서</strong></p>
</div>

---

## 🧭 목차

- [📌 프로젝트 소개](#project-overview)
- [👥 팀원 소개](#team)
- [✨ 주요 기능](#features)
- [🔄 서비스 흐름](#service-flow)
- [🏗️ 시스템 아키텍처](#system-architecture)
- [🗃️ ERD](#erd)
- [📖 API 명세서](#api-spec)
- [🧰 기술 스택](#tech-stack)
- [⚙️ 핵심 기술](#core-technology)
- [🗂️ 프로젝트 구조](#project-structure)
- [🛠️ 개발자 안내](#developer-guide)
- [🏷️ 버전 태그](#version-tags)
- [📚 관련 문서](#related-docs)

---

<a id="project-overview"></a>

## 📌 프로젝트 소개

Re:Solve는 개인 문제 풀이와 스터디를 지원하고, 대회와 모의 코딩 테스트를 통해 실전 연습을 할 수 있도록 만드는 서비스입니다.

### 핵심 목표

---

<a id="team"></a>

## 👥 팀원 소개

---

<a id="features"></a>

## ✨ 주요 기능

---

<a id="service-flow"></a>

## 🔄 서비스 흐름

---

<a id="system-architecture"></a>

## 🏗️ 시스템 아키텍처

---

<a id="erd"></a>

## 🗃️ ERD

---

<a id="api-spec"></a>

## 📖 API 명세서

---

<a id="tech-stack"></a>

## 🧰 기술 스택

### Backend

![Java 21](https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)<br/>
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring_Boot_4.1.1-6DB33F?style=flat-square&logo=springboot&logoColor=white)<br/>
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)<br/>
![Spring MVC](https://img.shields.io/badge/Spring_MVC-6DB33F?style=flat-square&logo=spring&logoColor=white)<br/>
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-59666C?style=flat-square&logo=hibernate&logoColor=white)<br/>
![Spring Cloud Gateway](https://img.shields.io/badge/Spring_Cloud_Gateway-6DB33F?style=flat-square&logo=spring&logoColor=white)

### Frontend

### Database

![MySQL 8.4](https://img.shields.io/badge/MySQL_8.4-4479A1?style=flat-square&logo=mysql&logoColor=white)<br/>
![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white)

### Cache·Lock

![Redis 7.4](https://img.shields.io/badge/Redis_7.4-DC382D?style=flat-square&logo=redis&logoColor=white)<br/>
![Redisson](https://img.shields.io/badge/Redisson-B71C1C?style=flat-square)

### API 문서

![Swagger UI](https://img.shields.io/badge/Swagger_UI-85EA2D?style=flat-square&logo=swagger&logoColor=173647)

### Monitoring

![Spring Boot Actuator](https://img.shields.io/badge/Spring_Boot_Actuator-6DB33F?style=flat-square&logo=springboot&logoColor=white)

### Test

![JUnit 5](https://img.shields.io/badge/JUnit_5-25A162?style=flat-square&logo=junit5&logoColor=white)<br/>
![Testcontainers](https://img.shields.io/badge/Testcontainers-2496ED?style=flat-square&logo=docker&logoColor=white)

### Code Quality

![Spotless](https://img.shields.io/badge/Spotless-4285F4?style=flat-square)<br/>
![Checkstyle](https://img.shields.io/badge/Checkstyle-03C75A?style=flat-square)

### Infra·Deploy

![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white)<br/>
![Docker Compose](https://img.shields.io/badge/Docker_Compose-2496ED?style=flat-square&logo=docker&logoColor=white)<br/>
![Nginx](https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white)

---

<a id="core-technology"></a>

## ⚙️ 핵심 기술

---

<a id="project-structure"></a>

## 🗂️ 프로젝트 구조

```text
re-solve
├── api-gateway/
├── member-service/
├── problem-service/
├── judge-service/
├── contest-service/
├── study-service/
├── notification-service/
├── integration-service/
├── frontend/
├── nginx/
├── config/
├── .github/
├── .env.example
├── Dockerfile
├── docker-compose.yml
├── build.gradle
└── settings.gradle
```

---

<a id="developer-guide"></a>

## 🛠️ 개발자 안내

<details>
<summary><strong>로컬 실행 및 검증</strong></summary>

### 로컬 실행

#### 요구 사항

- Java 21
- Docker와 Docker Compose

#### 환경 변수 설정

저장소 루트에서 예시 파일을 복사한 뒤 `.env`의 `MYSQL_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_PASSWORD`를 설정합니다.

```bash
cp .env.example .env
```

`.env`에는 실제 비밀번호를 넣고 Git에 커밋하지 않습니다.

#### 백엔드와 인프라 실행

```bash
./gradlew clean bootjar
docker-compose up -d --build
docker-compose ps
```

로컬 화면은 [http://127.0.0.1:8080/](http://127.0.0.1:8080/)에서 확인할 수 있습니다. 화면의 버튼으로 API Gateway 연결 상태를 확인할 수 있습니다.

실행 중인 컨테이너를 종료할 때는 다음 명령을 사용합니다.

```bash
docker-compose down
```

### 검증

</details>

---

<a id="version-tags"></a>

## 🏷️ 버전 태그

---

<a id="related-docs"></a>

## 📚 관련 문서
