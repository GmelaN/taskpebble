# TaskPebble

프로젝트 **C** — 독립형 작업 기록. APM 시스템/WAS 에이전트 설치 및 관측을 위한 작은 독립형 웹앱입니다.
다른 웹앱을 호출하지 않습니다. 등록·조회·삭제 요청이 실제 SQLite JDBC 쿼리를 실행합니다.

## 환경 및 빌드

- 실행: **Tomcat 9 / Java 8 이상** (`javax.servlet`). Tomcat 10/11에 그대로 배포할 수 없습니다.
- 빌드: JDK 17 이상, Maven 3.9 이상.
- 프레임워크 없이 Servlet + JDBC 사용. APM 에이전트는 WAR에 포함하지 않습니다.

```sh
mvn --batch-mode verify
```

결과: `target/taskpebble.war`. 테스트는 임시 Tomcat에서 HTTP 등록·조회·삭제, SQLite 재시작 후 유지,
입력 검증, HTML 이스케이프, DB 파일 HTTP 접근 차단을 확인합니다.

## 배포

Tomcat의 `unpackWARs="true"` 설정(기본값)을 사용하십시오.

```sh
cp taskpebble.war "$CATALINA_BASE/webapps/"
```

- 화면: `http://TOMCAT:8080/taskpebble/`
- DB 포함 상태 점검: `GET /taskpebble/health`
- 등록: `POST /taskpebble/create` (`title`, `detail` 폼 필드)
- 삭제: `POST /taskpebble/delete` (`id` 폼 필드)
- 인스턴스 표시: Tomcat 시작 전에 `APM_INSTANCE_NAME=tomcat-1` 등으로 환경변수 설정.
- JDBC/애플리케이션 오류는 Tomcat 로그에 기록합니다. 응답에는 `X-Request-ID`를 포함합니다.

## SQLite

첫 실행 시 `webapps/taskpebble/WEB-INF/db/taskpebble.sqlite`가 자동 생성됩니다.
Tomcat 실행 계정에 해당 디렉터리의 쓰기 권한이 필요합니다. DB 파일은 HTTP로 제공하지 않습니다.
각 VM/웹앱은 자기 DB를 사용하며 데이터는 동기화하지 않습니다. 한 Tomcat에 배포된 웹앱은 JVM 자원을 공유합니다.
샘플 데이터는 시작 시 테이블이 비어 있으면 한 건 생성합니다. 화면은 최근 100건을 표시합니다.
재배포로 풀린 웹앱 폴더가 교체되면 DB가 사라질 수 있으므로 필요한 데이터는 Tomcat을 중지하고
`WEB-INF/db` 디렉터리를 백업한 뒤 재배포·복원하십시오.

## GitHub Release

`main`/PR에서 빌드·테스트를 수행하고 WAR를 Actions artifact로 보관합니다.
`v*` 태그를 푸시하면 검증을 통과한 WAR와 `SHA256SUMS`를 GitHub Release에 첨부합니다.

```sh
git tag v1.0.0
git push origin v1.0.0
```

인증·사용자별 데이터 분리 없이 동작하는 테스트베드용 예제입니다.
