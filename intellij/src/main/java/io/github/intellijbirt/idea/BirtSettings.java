package io.github.intellijbirt.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/** Settings > Tools > BIRT 4.8 — 비워 두면 플러그인 안의 것을 씀 */
@Service(Service.Level.APP)
@State(name = "IntelliJBirtSettings", storages = @Storage("intellij-birt.xml"))
public final class BirtSettings implements PersistentStateComponent<BirtSettings.State> {

    public static final class State {
        /** 설계 화면 서버를 실행할 Java 폴더 (비우면 IntelliJ 내장 Java) */
        public String javaHome = "";
        /** 미리보기를 허용할 DB 주소 (쉼표 구분, 비우면 제한 없음) — 개발 DB만 적어 두면 그 밖은 실행 안 함 */
        public String allowHosts = "";
        /** 미리보기를 막을 DB 주소 (쉼표 구분) */
        public String denyHosts = "";
        /**
         * JDBC 드라이버 jar (세미콜론 구분) — 이클립스 Manage Drivers처럼. 리포트 프로젝트의 WEB-INF/lib에 드라이버가 있으면
         * 그걸 쓰므로(이클립스가 프로젝트 빌드 경로에서 찾는 것과 같음) 프로젝트 밖 리포트에만 필요
         */
        public String driverJars = "";
        /** (선택) 화면 편집용 이클립스(BIRT 디자이너) 실행 파일 — 지정하면 "이클립스 BIRT 디자이너로 열기" 메뉴가 생김 */
        public String eclipseExe = "";
    }

    private State state = new State();

    public static BirtSettings getInstance() {
        return ApplicationManager.getApplication().getService(BirtSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = loaded;
    }
}
