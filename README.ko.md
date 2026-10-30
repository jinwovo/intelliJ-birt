# intelliJ-birt

[English](README.md) | 한국어 | [日本語](README.ja.md) | [简体中文](README.zh-CN.md)

IntelliJ IDEA에서 Eclipse BIRT 4.8 리포트(`*.rptdesign`)를 이클립스 BIRT 디자이너와 똑같이 편집하는 **비공식** 플러그인 (플러그인 이름: Report Designer for BIRT).
플러그인 안에 이클립스 BIRT 디자이너 4.8이 들어 있어서 이클립스·Java·BIRT를 따로 설치하지 않는다 (IntelliJ 내장 Java로 실행).

| 기능 | 내용 |
|---|---|
| 편집기 탭 | 아래 탭 Layout · Master Page · Script · XML Source. Layout·Master Page는 이클립스 BIRT 편집기 그대로 (팔레트·눈금자·표 손잡이·우클릭 메뉴·끌어서 크기 조절) |
| 도구 창 | BIRT Property Editor(아래), BIRT Data Explorer(왼쪽 아래), BIRT Outline·BIRT Resource Explorer(오른쪽) — 이클립스 보조 창 그대로. 설계 화면을 열었을 때만 나타남 |
| 동기화 | 설계 화면·Property Editor에서 고친 내용이 XML Source에 바로 반영. XML Source에서 고치고 Layout으로 가면 이클립스처럼 저장할지 물어봄 |
| 저장 | 이클립스처럼 Ctrl+S로만 (자동 저장 안 함). 저장 안 한 탭은 이름 앞에 `*`, 닫을 때 저장할지 물어봄 |
| 단축키 | 설계 화면 안 Ctrl+Z·Y·C·X·V·A·S |
| 구조 보기·문법 강조 | 데이터 소스(접속 주소만)·데이터셋·파라미터·본문, SQL·JavaScript 강조 |
| 검사·미리보기 | 저장한 파일을 BIRT 4.8 엔진으로 검사(오류 줄 표시), 우클릭 > BIRT 미리보기 PDF·HTML·다른 형식(DOC·DOCX·PPT·PPTX·ODT·ODS·PostScript) — 이클립스 Run > View Report와 같은 형식 |

BIRT 4.8 디자이너의 편집기 안 Preview 탭은 BIRT가 스스로 꺼 두었으므로(Bugzilla 421879) 이 플러그인에도 없다. 미리보기는 위 메뉴로 한다.

## 판 (Releases)

| 판 | 파일 | 크기 | 데이터 소스 | 출력 형식 |
|---|---|---|---|---|
| 풀판 | `intellij-birt-<버전>.zip` | 약 145MB | 공식 디자이너와 같음 — JDBC, Flat File(CSV), Excel, XML, Web Services, MongoDB, Hive, POJO, 스크립트, 샘플 DB(Classic Models) | PDF·HTML·DOC·DOCX·PPT·PPTX·ODT·ODS·PostScript |
| 경량판 | `intellij-birt-lite-<버전>.zip` | 약 123MB | JDBC, POJO, 스크립트 | DOCX 빼고 같음 |

편집기·도구 창·검사·저장은 두 판이 같다. 둘 중 하나만 설치한다 (플러그인 id가 같아서 바꿔 설치하면 덮어씀).
경량판에서 풀판 전용 데이터 소스를 쓰는 리포트를 열면 그 데이터 소스는 검사에서 "invalid manifest" 오류로 나오고 미리보기가 안 된다.

## 설치

Windows, IntelliJ IDEA 2025.1 이상 (2025.1·2026.1·2026.2 호환 검사)
> Settings > Plugins > ⚙ > Install Plugin from Disk > 받은 zip (압축 풀지 않음) > 재시작

## 설정 (Settings > Tools > BIRT 4.8) — 전부 비워 둬도 동작

| 항목 | 기본 |
|---|---|
| Java | IntelliJ 내장 Java |
| 미리보기 허용 DB | 없음 (제한 없음). 적으면 그 주소에 접속하는 리포트만 실행 |
| 미리보기 차단 DB | 없음. 적어 두면 그 주소에 접속하는 리포트는 미리보기·데이터셋 Preview Results를 하지 않음 |
| JDBC 드라이버 jar | 없음. 리포트 프로젝트의 `WEB-INF/lib`에 있으면 그걸 씀 |
| (선택) 이클립스 | 없음 |

DB 접속 정보는 리포트 파일에 적힌 것을 쓰고, 드라이버는 이클립스 BIRT 디자이너처럼 리포트가 있는 프로젝트의 `WEB-INF/lib`
(웹 프로젝트의 Web App Libraries)에서 찾는다. **플러그인에는 DB 드라이버가 들어 있지 않다.** `WEB-INF/lib`에서는 파일 이름으로 드라이버를 고르므로
(`jdbc`, `mysql`, `mariadb`, `postgresql`, `mssql`, `db2jcc`, `jtds`, `tibero`, `altibase`, `cubrid`, `ngdbc`) 그 밖의 드라이버는 설정이나 디자이너의 Manage Drivers로 넣는다.

허용·차단 목록은 리포트 위쪽 폴더의 `.intellij-birt.properties`로도 둘 수 있다 (리포트에서 위로 올라가며 처음 만나는 파일, IDE 설정과 합침).
팀이 같이 지킬 값(운영 DB 차단 등)은 이 파일을 저장소에 두면 사람마다 IDE 설정을 하지 않아도 된다.

```properties
preview.allowHosts=devdb:1521
preview.denyHosts=proddb:1521
```

### DB 보호 범위

- 미리보기(모든 출력 형식): 리포트의 접속 주소(`odaURL`)로 검사 — 모든 DB
- 설계 화면의 DB 접속(데이터셋 Preview Results·열 가져오기·Test Connection): 아래 드라이버만 (BIRT가 드라이버 클래스 이름으로 연결을 만들기 때문)
  Oracle, MySQL, MariaDB, PostgreSQL, SQL Server, jTDS, Db2, SAP HANA, Tibero, Altibase, CUBRID
- 접속 주소 문자열을 비교하는 실수 방지 장치이지 보안 경계는 아니다 (스크립트로 주소를 바꾸는 리포트 등은 막지 못함)

### 주의

- 리포트를 열면 그 프로젝트 `WEB-INF/lib`의 드라이버 jar를 불러오고, 미리보기는 리포트 안 스크립트를 실행한다 (이클립스 BIRT와 같음).
  모르는 사람이 준 프로젝트·리포트는 내용을 확인하고 연다.
- 작업 파일은 IDE 시스템 폴더의 `intellij-birt/` 아래에만 둔다. 서버 임시 파일(`tmp/`)은 서버를 띄울 때·끌 때, 미리보기 결과(`preview/`)는 IDE를 끌 때 지운다.

## 구조

플러그인이 `designer/` 폴더의 이클립스 번들로 OSGi를 별도 JVM에서 띄워 두고(설계 화면 서버, `BirtDesignerServer`),
서버가 연 BIRT 편집기·보조 창의 창을 IntelliJ 탭·도구 창 안에 끼워 넣는다(Windows 창 부모 바꾸기). 표준입력 한 줄 명령 / 표준출력 `@@ ` 응답.
네트워크 포트는 열지 않는다. rptdesign 편집기가 하나도 없으면 1분 뒤 서버 종료.

| 경로 | 내용 |
|---|---|
| `intellij/` | 플러그인 — 편집기 탭·도구 창(`BirtEmbeddedEditor`·`BirtViewWindows`), 동기화(`BirtDesignerSync`), 저장(`BirtSaving`), 구조 보기, 검사·미리보기 연결(`BirtCliRunner`) |
| `render/` | 설계 화면 서버 OSGi 번들 — 명령 처리(`App`), 편집기·보조 창 끼워 넣기(`EmbeddedEditors`·`EmbeddedViews`), DB 보호(`GuardedConnectionFactory`) |
| `render/bundles.txt` | 플러그인에 넣는 이클립스 번들 목록 (경량판) |
| `render/bundles-full.txt` | 풀판에 더 넣는 번들 (데이터 소스·DOCX 출력) |
| `cli/src` | 검사·파라미터·미리보기(출력 형식별) 명령 코드 — render 번들에 같이 컴파일되어 서버 안에서 실행 |

플러그인 zip은 거의 전부 이클립스 BIRT 디자이너 번들이다.

## 빌드

- BIRT 디자이너 4.8이 설치된 이클립스(번들을 가져옴), JDK 8(render — Gradle toolchain이 찾음). IntelliJ SDK는 지원 범위 맨 아래(2025.1)를 Gradle이 받아서 씀
- `local.properties.example`을 `local.properties`로 복사해서 `eclipsePlugins`(필수), `ideaPath`(선택 — 호환 검사에 넣을 설치본) 지정
- Gradle은 IntelliJ 내장 JBR로 실행
- 이클립스의 JDBC 번들 `drivers` 폴더에 넣어 둔 드라이버 jar는 배포본에 넣지 않는다

```
set JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr
gradlew :intellij:buildPlugin                    (풀판)
gradlew :intellij:buildPlugin -Pedition=lite     (경량판)
gradlew :intellij:verifyPlugin                   (2025.1·2026.1·설치본 호환 검사)
```

결과: `intellij/build/distributions/intellij-birt-<버전>.zip`, `intellij-birt-lite-<버전>.zip`

## 라이선스

- 이 저장소의 코드: MIT 라이선스 ([LICENSE](LICENSE)). 단 `render/java-modern.profile`은 이클립스 Equinox 파일을 고친 것이라 EPL-1.0
- 들어 있는 제3자 구성 요소와 라이선스: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) (배포 zip 안에도 있음), EPL-1.0 원문: [licenses/EPL-1.0.html](licenses/EPL-1.0.html)
- 배포 zip에 들어가는 Eclipse BIRT 4.8과 의존 번들은 고치지 않은 원본이며, 각 번들 안 `about.html`의 라이선스(주로 EPL-1.0, Apache-2.0 등)를 따른다.
  BIRT 소스: https://github.com/eclipse-birt/birt (4.8 릴리스, 빌드 v201806261756), 이클립스 플랫폼·DTP 소스는 각 번들의 `about.html`에 적힌 곳.
- Eclipse·BIRT는 Eclipse Foundation의 상표이며, 이 프로젝트는 Eclipse Foundation과 관계가 없다.
