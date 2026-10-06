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
SQLite 모드에서는 각 VM/웹앱이 자기 DB를 사용합니다. MariaDB 설정은 아래를 참고하십시오. 한 Tomcat에 배포된 웹앱은 JVM 자원을 공유합니다.
샘플 데이터는 시작 시 ID 1이 없으면 한 건 생성합니다. 화면은 최근 100건을 표시합니다.
재배포로 풀린 웹앱 폴더가 교체되면 DB가 사라질 수 있으므로 필요한 데이터는 Tomcat을 중지하고
`WEB-INF/db` 디렉터리를 백업한 뒤 재배포·복원하십시오.

## GitHub Release

`main`/PR에서 빌드·테스트를 수행하고 WAR를 Actions artifact로 보관합니다.
`v*` 태그를 푸시하면 검증을 통과한 WAR와 `SHA256SUMS`를 GitHub Release에 첨부합니다.

```sh
git tag v1.1.0
git push origin v1.1.0
```

인증·사용자별 데이터 분리 없이 동작하는 테스트베드용 예제입니다.

## 중앙 MariaDB 설정 (.env)

같은 프로젝트의 두 Tomcat 인스턴스는 같은 DB를 바라보도록 설정합니다.
A/B/C는 같은 MariaDB 서버를 사용하되 각각 `memoharbor`, `cataloggrove`, `taskpebble` DB를 사용합니다.
DB와 계정은 관리자 권한으로 먼저 생성하십시오. 앱은 시작할 때 테이블과 샘플 한 건을 준비합니다.

프로젝트 소스 루트에서 다음 파일을 만드십시오.

```sh
cp .env.example .env
```

```dotenv
DB_URL=jdbc:mariadb://DB_SERVER:3306/taskpebble
DB_USER=taskpebble
DB_PASSWORD='your-password'
```

Tomcat은 Git 저장소 위치를 알 수 없으므로 `bin/setenv.sh`에서 프로젝트 `.env`의 절대 경로를 지정하십시오.
파일은 각 WAS VM에 있어야 하며, 실행 계정에 읽기 권한이 필요합니다.

```sh
CATALINA_OPTS="$CATALINA_OPTS -Dtaskpebble.env=/opt/projects/taskpebble/.env"
export CATALINA_OPTS
```

여러 앱을 같은 Tomcat에서 실행하면 앱별 `-Dmemoharbor.env=...`, `-Dcataloggrove.env=...`,
`-Dtaskpebble.env=...`를 함께 지정합니다. 각 옵션은 해당 앱에만 적용됩니다.
경로 지정 없이 배포할 경우 풀린 웹앱 루트의 `webapps/taskpebble/.env`를 읽습니다.
그 파일도 HTTP 접근이 차단됩니다. 재배포 시 덮어쓰지 않도록 외부 프로젝트 루트 경로를 권장합니다.

- `.env` 없음: 기존 SQLite 모드.
- `.env` 있음: MariaDB 모드. 연결 실패·설정 누락 시 시작 실패하며 SQLite로 전환하지 않습니다.
- 명시한 `.env` 경로가 없음: 시작 실패.
- `.env` 변경 후 웹앱/Tomcat 재시작 필요.
- UTF-8 `KEY=value`, 주석 줄, 작은따옴표/큰따옴표로 감싼 값 지원. 변수 치환·여러 줄 값은 지원하지 않습니다.
- `.env`는 Git 및 WAR에서 제외합니다. `.env.example`만 공개합니다.
- 연결 시간 제한 5초, 소켓 시간 제한 10초. DB 세션 시간대는 UTC입니다.

예시 관리자 SQL (실제 비밀번호와 WAS 주소로 변경):

```sql
CREATE DATABASE IF NOT EXISTS taskpebble CHARACTER SET utf8mb4;
CREATE USER 'taskpebble'@'WAS_IP' IDENTIFIED BY 'your-password';
GRANT SELECT, INSERT, DELETE, CREATE ON taskpebble.* TO 'taskpebble'@'WAS_IP';
```

WAS 두 대에서 접속하려면 각 주소에 계정을 생성하거나 환경에 맞는 호스트 범위를 사용하십시오.
기존 SQLite 데이터는 자동으로 MariaDB에 복사되지 않습니다.

### MariaDB 검증

GitHub Actions는 실제 MariaDB 11.4 서비스를 띄워 두 Tomcat 인스턴스 간 데이터 공유와 재시작을 검증합니다.
로컬에서 테스트할 때는 전용 테스트 DB의 `MARIADB_TEST_URL`, `MARIADB_TEST_USER`,
`MARIADB_TEST_PASSWORD` 환경변수를 설정하고 `mvn verify`를 실행하십시오.
미설정 시 MariaDB 통합 테스트는 건너뛰며 SQLite·설정 파서 테스트는 실행합니다.
