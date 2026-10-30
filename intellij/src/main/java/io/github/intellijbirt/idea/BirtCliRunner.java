package io.github.intellijbirt.idea;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 검사·파라미터·미리보기(birt-cli 명령)를 설계 화면 서버(BirtDesignerServer) 안에서 실행하고 JSON 결과를 돌려줌 —
 * 설계 화면과 같은 BIRT 4.8 엔진이라 따로 런타임을 두지 않음. DB 드라이버는 리포트 프로젝트의 WEB-INF/lib에서 (이클립스와 같음)
 */
public final class BirtCliRunner {

    private BirtCliRunner() {
    }

    /** 실행 결과: exitCode 0 정상, 1 리포트 오류·실행 실패, 2 사용법, 3 운영 DB라 거부 */
    public static final class Result {
        public final int exitCode;
        public final @Nullable JsonObject json;
        public final String stderr;

        Result(int exitCode, @Nullable JsonObject json, String stderr) {
            this.exitCode = exitCode;
            this.json = json;
            this.stderr = stderr;
        }

        public boolean ok() {
            return exitCode == 0 && json != null && json.has("ok") && json.get("ok").getAsBoolean();
        }

        public String error() {
            if (json != null && json.has("error") && !json.get("error").isJsonNull()) {
                return json.get("error").getAsString();
            }
            String tail = stderr == null ? "" : StringUtil.last(stderr.trim(), 600, true).toString();
            return "birt-cli 종료 코드 " + exitCode + (tail.isEmpty() ? "" : " — " + tail);
        }
    }

    /** 설정 문제(Java·BIRT jar를 못 찾음) — 사용자에게 그대로 보여줄 메시지 */
    public static final class SetupException extends Exception {
        SetupException(String message) {
            super(message);
        }
    }

    public static Result run(@NotNull VirtualFile report, @NotNull List<String> args, int timeoutSeconds)
            throws SetupException {
        BirtDesignerServer server = BirtDesignerServer.getInstance();
        server.acquire();
        try {
            if (!args.isEmpty() && "render".equals(args.get(0))) {
                // DB에 접속할 수 있으니 설계 화면과 같게: 프로젝트 드라이버를 넣고 DB 보호 목록을 이 리포트 것으로
                BirtDesignerServer.installDrivers(report);
                String[] guard = guardLists(report);
                server.send("guard", guard[0], guard[1]).get(60, TimeUnit.SECONDS);
            }
            String[] r = server.send("cli", args.toArray(new String[0])).get(timeoutSeconds, TimeUnit.SECONDS);
            // 결과 칸: 종료 코드, JSON (JSON 안 탭으로 나뉘었으면 다시 붙임)
            String json = r.length > 1 ? String.join("\t", Arrays.copyOfRange(r, 1, r.length)) : "";
            return new Result(r.length > 0 ? Integer.parseInt(r[0]) : 1, parse(json), "");
        } catch (TimeoutException e) {
            return new Result(-1, null, "시간 초과 (" + timeoutSeconds + "초)");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new SetupException("설계 화면 서버 실행 실패: " + c.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, null, "중단됨");
        } finally {
            server.release();
        }
    }

    private static @Nullable JsonObject parse(String stdout) {
        // 마지막 줄이 결과 JSON (그 앞에 BIRT가 찍은 줄이 있을 수 있음)
        String[] lines = stdout.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{")) {
                try {
                    return JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 찾기

    /** 설정한 Java → 없으면 IntelliJ 내장 Java (BIRT 4.8이 그대로 돎 — 따로 설치할 필요 없음) */
    static Path findJava() throws SetupException {
        String configured = BirtSettings.getInstance().getState().javaHome;
        if (StringUtil.isNotEmpty(configured)) {
            Path exe = javaExe(Paths.get(configured));
            if (exe == null) {
                throw new SetupException("설정한 Java 폴더에 bin/java가 없습니다: " + configured + " (Settings > Tools > BIRT 4.8)");
            }
            return exe;
        }
        Path exe = javaExe(Paths.get(System.getProperty("java.home")));
        if (exe == null) {
            throw new SetupException("IntelliJ 내장 Java를 찾지 못했습니다 — Settings > Tools > BIRT 4.8에서 Java를 지정하세요");
        }
        return exe;
    }

    static @Nullable Path javaExe(Path home) {
        Path exe = home.resolve("bin").resolve(SystemInfo.isWindows ? "java.exe" : "java");
        return Files.isRegularFile(exe) ? exe : null;
    }

    private static List<Path> configuredDriverJars() {
        List<Path> jars = new ArrayList<>();
        for (String jar : BirtSettings.getInstance().getState().driverJars.split(";")) {
            if (!jar.isBlank() && Files.isRegularFile(Paths.get(jar.trim()))) {
                jars.add(Paths.get(jar.trim()));
            }
        }
        return jars;
    }

    private static final Pattern DRIVER_JAR = Pattern.compile("(?i).*(jdbc|tibero|mysql|mariadb|postgresql|mssql|db2jcc|jtds|altibase|cubrid|ngdbc).*\\.jar");

    /** 리포트가 쓸 드라이버 jar — 설정한 것 + 리포트 프로젝트 WEB-INF/lib의 드라이버(이름으로 고름, 이클립스 번들 제외) */
    static List<Path> driverJars(VirtualFile report) {
        List<Path> jars = configuredDriverJars();
        Path web = webInfLib(report);
        if (web != null) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(web, "*.jar")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith("org.eclipse.") && DRIVER_JAR.matcher(name).matches()) {
                        jars.add(p);
                    }
                }
            } catch (IOException ignored) {
                // 못 읽으면 설정한 것만
            }
        }
        return jars;
    }

    /** 리포트에서 위로 올라가며 처음 만나는 WEB-INF/lib (웹 프로젝트의 Web App Libraries) */
    static @Nullable Path webInfLib(VirtualFile report) {
        for (VirtualFile dir = report.getParent(); dir != null; dir = dir.getParent()) {
            for (Path lib : new Path[] {Paths.get(dir.getPath(), "WEB-INF", "lib"), Paths.get(dir.getPath(), "WebContent", "WEB-INF", "lib")}) {
                if (Files.isDirectory(lib)) {
                    return lib;
                }
            }
        }
        return null;
    }

    /** 플러그인 폴더 — 이 플러그인 jar(lib/…)에서 위로 올라가며 designer/가 있는 곳 (플러그인 정보 API는 2026.2에서 내부용이라 안 씀) */
    static Path pluginPath() throws SetupException {
        String jar = PathManager.getJarPathForClass(BirtCliRunner.class);
        for (Path p = jar == null ? null : Paths.get(jar).getParent(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("designer"))) {
                return p;
            }
        }
        throw new SetupException("플러그인 폴더를 찾지 못했습니다: " + jar);
    }

    private static final Pattern ODA_URL = Pattern.compile("<property name=\"odaURL\">([^<]*)</property>");

    /** 리포트의 데이터 소스 접속 주소 (jdbc:...@ 뒤 호스트 부분만 — 계정 정보 없음) */
    static List<String> dataSourceHosts(VirtualFile report) {
        List<String> hosts = new ArrayList<>();
        try {
            Matcher m = ODA_URL.matcher(VfsUtilCore.loadText(report));
            while (m.find()) {
                String url = m.group(1);
                String host = url.lastIndexOf('@') >= 0 ? url.substring(url.lastIndexOf('@') + 1) : url;
                if (!hosts.contains(host)) {
                    hosts.add(host);
                }
            }
        } catch (IOException ignored) {
            // 못 읽으면 확인 없이 진행하지 않도록 빈 목록 대신 표시용 문구
            hosts.add("(리포트를 읽지 못함)");
        }
        return hosts;
    }

    /**
     * 미리보기 DB 허용·차단 목록 → --allow / --deny 옵션들.
     * IDE 설정 + 리포트에서 위로 올라가며 처음 만나는 프로젝트 설정 파일(.intellij-birt.properties)을 합침 —
     * 팀이 같이 지킬 값(운영 DB 차단 등)은 저장소의 설정 파일에 두면 사람마다 IDE 설정을 안 해도 됨.
     */
    static List<String> hostArgs(VirtualFile report) {
        BirtSettings.State s = BirtSettings.getInstance().getState();
        Properties project = projectConfig(report);
        List<String> args = new ArrayList<>();
        addHosts(args, "--allow", s.allowHosts, project.getProperty("preview.allowHosts"));
        addHosts(args, "--deny", s.denyHosts, project.getProperty("preview.denyHosts"));
        return args;
    }

    /** 설계 화면 서버의 DB 보호용 {허용 주소들, 차단 주소들} (쉼표 구분) — 미리보기와 같은 목록 */
    static String[] guardLists(VirtualFile report) {
        List<String> args = hostArgs(report);
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        for (int i = 0; i + 1 < args.size(); i += 2) {
            ("--allow".equals(args.get(i)) ? allow : deny).add(args.get(i + 1));
        }
        return new String[] {String.join(",", allow), String.join(",", deny)};
    }

    static final String PROJECT_CONFIG = ".intellij-birt.properties";

    static Properties projectConfig(VirtualFile report) {
        Properties p = new Properties();
        for (VirtualFile dir = report.getParent(); dir != null; dir = dir.getParent()) {
            VirtualFile f = dir.findChild(PROJECT_CONFIG);
            if (f != null && !f.isDirectory()) {
                try (InputStreamReader r = new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8)) {
                    p.load(r);
                } catch (IOException ignored) {
                    // 읽지 못하면 IDE 설정만 씀
                }
                break;
            }
        }
        return p;
    }

    private static void addHosts(List<String> args, String option, String... lists) {
        for (String list : lists) {
            if (StringUtil.isEmpty(list)) {
                continue;
            }
            for (String h : list.split("[,\\s]+")) {
                if (!h.isEmpty()) {
                    args.add(option);
                    args.add(h);
                }
            }
        }
    }
}
