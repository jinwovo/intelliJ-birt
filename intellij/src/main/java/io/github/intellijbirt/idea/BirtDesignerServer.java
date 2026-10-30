package io.github.intellijbirt.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * 설계 화면 서버 — 이클립스 BIRT 디자이너 4.8 코드(플러그인 designer/ 폴더의 OSGi 번들)를 별도 JVM으로 띄워 두고
 * Layout·Master Page 화면을 PNG로 받음. 이클립스와 같은 코드로 그려서 픽셀까지 같음.
 * 처음 그릴 때 시작(약 5초, 메모리 약 200MB), 그 뒤 그리기는 건당 0.1~0.4초. 쉬는 동안 CPU는 거의 안 씀.
 * 열린 rptdesign 편집기가 하나도 없으면 1분 뒤 종료 (다시 열면 다시 시작). 죽으면 다음 요청 때 다시 시작, IDE 끌 때 종료.
 */
public final class BirtDesignerServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(BirtDesignerServer.class);

    /** 그린 결과: 이미지 크기, 용지 위치(이미지 좌표), 요소 위치 파일 */
    public static final class Rendered {
        public final File png;
        public final File bounds;
        public final int width;
        public final int height;
        public final int pageX;
        public final int pageY;
        public final long millis;

        Rendered(File png, int width, int height, int pageX, int pageY, long millis) {
            this.png = png;
            this.bounds = new File(png.getPath().substring(0, png.getPath().length() - 4) + ".json");
            this.width = width;
            this.height = height;
            this.pageX = pageX;
            this.pageY = pageY;
            this.millis = millis;
        }
    }

    private final Object lock = new Object();
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<String, CompletableFuture<String[]>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<String[]>> listeners = new CopyOnWriteArrayList<>();
    private Process process;
    private Writer commands;
    private CompletableFuture<Void> ready;
    private int users;
    private ScheduledFuture<?> idleStop;

    public static BirtDesignerServer getInstance() {
        return ApplicationManager.getApplication().getService(BirtDesignerServer.class);
    }

    /** 이 플러그인이 쓰는 폴더 (IDE 시스템 폴더 아래) — config·workspace·server.log, tmp(서버 임시 파일), preview(미리보기 결과) */
    static File home() {
        return new File(PathManager.getSystemPath(), "intellij-birt");
    }

    /** 미리보기 결과 (PDF·HTML·XML Source 옆 그림) — IDE를 끌 때 지움 */
    static File previewDir() {
        return new File(home(), "preview");
    }

    /** 설계 화면 서버의 임시 폴더 (서버 java.io.tmpdir — 동기화 파일·BIRT 엔진 임시 파일) — 서버를 띄울 때·끌 때 지움 */
    private static File tmpDir() {
        return new File(home(), "tmp");
    }

    private static final long LOG_LIMIT = 5L * 1024 * 1024;
    private boolean cleanedPreview;

    /**
     * 리포트가 쓸 JDBC 드라이버를 설계 화면 JDBC 번들의 drivers 폴더에 복사 — 이클립스 Manage Drivers가 넣는 자리와 같음.
     * 드라이버는 리포트 프로젝트의 WEB-INF/lib(이클립스가 프로젝트 빌드 경로에서 찾는 것과 같음)와 설정한 jar.
     * 같은 것(이름·크기)이 있으면 그대로. BIRT는 드라이버 클래스를 못 찾으면 폴더를 다시 읽으므로 서버를 다시 띄울 필요 없음
     */
    static void installDrivers(com.intellij.openapi.vfs.VirtualFile report) {
        try {
            Path drivers = null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(BirtCliRunner.pluginPath().resolve("designer").resolve("plugins"),
                    "org.eclipse.birt.report.data.oda.jdbc_*")) {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) {
                        drivers = p.resolve("drivers");
                    }
                }
            }
            if (drivers == null) {
                return;
            }
            Files.createDirectories(drivers);
            for (Path jar : BirtCliRunner.driverJars(report)) {
                Path target = drivers.resolve(jar.getFileName().toString());
                if (!Files.isRegularFile(target) || Files.size(target) != Files.size(jar)) {
                    Files.copy(jar, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            LOG.warn("JDBC 드라이버 복사 실패", e);
        }
    }

    /** 설계 화면 편집기가 열림 — 종료 예약 취소 */
    public void acquire() {
        synchronized (lock) {
            users++;
            if (idleStop != null) {
                idleStop.cancel(false);
                idleStop = null;
            }
        }
    }

    /** 설계 화면 편집기가 닫힘 — 마지막이면 1분 뒤 서버 종료 (그 사이 다시 열면 취소) */
    public void release() {
        synchronized (lock) {
            users = Math.max(0, users - 1);
            if (users == 0 && process != null && idleStop == null) {
                idleStop = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                    synchronized (lock) {
                        idleStop = null;
                        if (users == 0) {
                            stop();
                        }
                    }
                }, 1, TimeUnit.MINUTES);
            }
        }
    }

    /**
     * @param design   리포트 파일 (상대 경로 이미지 등의 기준)
     * @param content  그릴 내용 — 저장 전 편집 내용을 쓴 파일 (저장된 그대로면 design)
     * @param viewport 화면 폭(px) — 이클립스처럼 용지를 이 폭 안에서 가운데 둠
     */
    public CompletableFuture<Rendered> render(File design, File content, File png, int viewport, boolean master) {
        return send("render", design.getPath(), content.getPath(), png.getPath(), String.valueOf(viewport),
                master ? "master" : "layout")
                .thenApply(f -> new Rendered(png, Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                        Integer.parseInt(f[2]), Integer.parseInt(f[3]), Long.parseLong(f[4])));
    }

    /** 서버 알림("@@ event 종류 키 …") 받기 — 서버 출력 스레드에서 불림, parent가 정리되면 해제 */
    public void addEventListener(Disposable parent, Consumer<String[]> listener) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    /**
     * 서버에 명령 하나 — 한 줄 "명령 번호 인자…"(탭 구분). 처음이면 서버를 띄움.
     * @return 응답 "@@ ok 번호 …"에서 번호 뒤의 칸들 ("@@ error"면 예외)
     */
    public CompletableFuture<String[]> send(String command, String... args) {
        CompletableFuture<String[]> result = new CompletableFuture<>();
        try {
            ensureStarted().get(120, TimeUnit.SECONDS);
            String id = String.valueOf(ids.incrementAndGet());
            pending.put(id, result);
            List<String> line = new ArrayList<>();
            line.add(command);
            line.add(id);
            line.addAll(Arrays.asList(args));
            synchronized (lock) {
                commands.write(String.join("\t", line) + "\n");
                commands.flush();
            }
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private CompletableFuture<Void> ensureStarted() throws IOException, BirtCliRunner.SetupException {
        synchronized (lock) {
            if (process != null && process.isAlive() && ready != null) {
                return ready;
            }
            ready = new CompletableFuture<>();
            Path designer = BirtCliRunner.pluginPath().resolve("designer");
            File plugins = designer.resolve("plugins").toFile();
            if (!plugins.isDirectory()) {
                throw new BirtCliRunner.SetupException("플러그인에 설계 화면 번들이 없습니다: " + plugins);
            }
            File home = home();
            File config = new File(home, "config");
            List<String> launcher = writeConfig(designer, plugins, config);
            if (!cleanedPreview) {
                // 지난번 IDE가 비정상 종료돼 남은 미리보기 결과 (이번 실행의 미리보기는 서버가 뜬 뒤에 생김)
                cleanedPreview = true;
                FileUtil.delete(previewDir());
            }
            File tmp = tmpDir();
            FileUtil.delete(tmp);
            FileUtil.createDirectory(tmp);
            File log = new File(home, "server.log");
            if (log.length() > LOG_LIMIT) {
                FileUtil.delete(log);
            }
            List<String> command = new ArrayList<>();
            command.add(BirtCliRunner.findJava().toString());
            command.add("-Xmx768m");
            command.add("-Djava.io.tmpdir=" + tmp.getPath());
            command.add("-jar");
            command.add(launcher.get(0));
            command.add("-install");
            command.add(designer.toString());
            command.add("-configuration");
            command.add(config.getPath());
            command.add("-data");
            command.add(new File(home, "workspace").getPath());
            command.add("-nosplash");
            command.add("-clearPersistedState");   // 지난번 창 배치를 되살리지 않음 (창은 안 보이게 숨김)
            command.add("-consoleLog");
            command.add("-application");
            command.add("io.github.intellijbirt.render.app");
            command.add("--serve");
            ProcessBuilder pb = new ProcessBuilder(command).directory(home)
                    .redirectError(ProcessBuilder.Redirect.appendTo(log));
            process = pb.start();
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Process p = process;
            CompletableFuture<Void> started = ready;
            Thread reader = new Thread(() -> readLoop(p, started), "birt-designer-server");
            reader.setDaemon(true);
            reader.start();
            return ready;
        }
    }

    /** 서버 출력 — "@@ "로 시작하는 줄만 (나머지는 이클립스 로그) */
    private void readLoop(Process p, CompletableFuture<Void> started) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("@@ ")) {
                    continue;
                }
                String[] f = line.substring(3).split("\t", -1);
                if ("ready".equals(f[0])) {
                    started.complete(null);
                } else if ("event".equals(f[0]) && f.length >= 3) {
                    String[] event = Arrays.copyOfRange(f, 1, f.length);
                    for (Consumer<String[]> l : listeners) {
                        try {
                            l.accept(event);
                        } catch (RuntimeException e) {
                            LOG.warn("BIRT 설계 화면 서버 알림 처리 실패", e);
                        }
                    }
                } else if ("ok".equals(f[0]) && f.length >= 2) {
                    CompletableFuture<String[]> r = pending.remove(f[1]);
                    if (r != null) {
                        r.complete(Arrays.copyOfRange(f, 2, f.length));
                    }
                } else if ("error".equals(f[0]) && f.length >= 3) {
                    CompletableFuture<String[]> r = pending.remove(f[1]);
                    if (r != null) {
                        r.completeExceptionally(new IOException(f[2]));
                    }
                }
            }
        } catch (IOException e) {
            LOG.info("BIRT 설계 화면 서버 출력 읽기 끝: " + e);
        }
        // 서버가 끝남 — 기다리던 요청은 실패로, 다음 요청 때 다시 시작
        IOException gone = new IOException("설계 화면 서버가 종료됐습니다 (로그: " + new File(home(), "server.log") + ")");
        started.completeExceptionally(gone);
        pending.values().forEach(r -> r.completeExceptionally(gone));
        pending.clear();
    }

    /**
     * OSGi 설정 — 번들 목록(bundles.info)과 config.ini를 IDE 시스템 폴더에 씀. 이클립스 설치본은 쓰지 않음.
     * 플러그인이 바뀌면(번들 목록이 다르면) OSGi 캐시를 지움.
     * @return {launcher jar 경로}
     */
    private static List<String> writeConfig(Path designer, File plugins, File config) throws IOException {
        FileUtil.createDirectory(config);
        List<String> list = Files.readAllLines(designer.resolve("bundles.txt"), StandardCharsets.UTF_8);
        StringBuilder info = new StringBuilder("#version=1\n");
        String framework = null;
        String launcher = null;
        String simpleConfigurator = null;
        String compatibility = null;
        for (String line : list) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",");
            File file = new File(plugins, f[2]);
            String url = file.toURI().toString();
            if ("launcher".equals(f[3])) {
                launcher = file.getPath();
                continue;
            }
            if ("0".equals(f[3])) {
                framework = url;
                continue;
            }
            if ("org.eclipse.equinox.simpleconfigurator".equals(f[0])) {
                simpleConfigurator = url;
            }
            if ("org.eclipse.osgi.compatibility.state".equals(f[0])) {
                compatibility = url;
            }
            info.append(f[0]).append(',').append(f[1]).append(',').append(url).append(',').append(f[3]).append(',')
                    .append(f[4]).append('\n');
        }
        File render = new File(plugins, "io.github.intellijbirt.render_1.0.0.jar");
        info.append("io.github.intellijbirt.render,1.0.0,").append(render.toURI()).append(",4,true\n");
        // 플러그인 업데이트로 내 번들만 바뀌어도(경로·버전은 그대로) 캐시를 새로 — 내용 값을 주석 줄로 넣어 비교
        CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(render.toPath()));
        info.append("#render=").append(Long.toHexString(crc.getValue())).append('\n');
        if (framework == null || launcher == null || simpleConfigurator == null) {
            throw new IOException("bundles.txt에 OSGi 프레임워크·launcher·simpleconfigurator가 없습니다");
        }

        File bundlesInfo = new File(config, "bundles.info");
        String old = bundlesInfo.isFile() ? new String(Files.readAllBytes(bundlesInfo.toPath()), StandardCharsets.UTF_8) : "";
        if (!old.equals(info.toString())) {
            // 번들이 바뀌면 캐시를 모두 새로 — OSGi 캐시만 지우면 확장 등록 캐시(org.eclipse.core.runtime)가 남는데,
            // 압축 푼 jar 날짜가 늘 같아서(1980) 바뀐 plugin.xml의 확장이 등록되지 않음
            File[] caches = config.listFiles(File::isDirectory);
            if (caches != null) {
                for (File cache : caches) {
                    FileUtil.delete(cache);
                }
            }
            Files.write(bundlesInfo.toPath(), info.toString().getBytes(StandardCharsets.UTF_8));
        }

        Properties ini = new Properties();
        ini.setProperty("osgi.install.area", designer.toUri().toString());
        ini.setProperty("osgi.configuration.cascaded", "false");
        ini.setProperty("osgi.framework", framework);
        ini.setProperty("osgi.bundles", "reference:" + simpleConfigurator + "@1:start");
        ini.setProperty("org.eclipse.equinox.simpleconfigurator.configUrl", bundlesInfo.toURI().toString());
        if (compatibility != null) {
            ini.setProperty("osgi.framework.extensions", "reference:" + compatibility);
        }
        ini.setProperty("osgi.bundles.defaultStartLevel", "4");
        ini.setProperty("eclipse.application", "io.github.intellijbirt.render.app");
        // 옛 업데이트 관리자가 설치 폴더 번들을 다시 검사하지 않게 — 켜 두면 서비스 시작이 30초 대기에 걸림
        ini.setProperty("org.eclipse.update.reconcile", "false");
        // java.* 부모 클래스로더 — Java 9 이후 java.sql 등은 boot가 아니라 플랫폼 클래스로더에 있음 (JDBC 드라이버에 필요)
        ini.setProperty("osgi.parentClassloader", "ext");
        // OSGi 3.11은 Java 8 기준 JDK 패키지 목록을 씀 — Java 11 이후 JDK에서 빠진 것(javax.annotation 등)을 뺀 목록으로.
        //   빠진 패키지는 번들(javax.annotation)이 대신 제공 — Java 8로 돌려도 그대로 동작
        ini.setProperty("osgi.java.profile", designer.resolve("java-modern.profile").toUri().toString());
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(new File(config, "config.ini").toPath()), StandardCharsets.ISO_8859_1)) {
            ini.store(w, "intelliJ-birt designer server");
        }
        List<String> result = new ArrayList<>();
        result.add(launcher);
        return result;
    }

    /** 서버 종료(임시 폴더도 지움) — 다음 그리기 요청 때 다시 시작 */
    private void stop() {
        synchronized (lock) {
            if (process != null && process.isAlive()) {
                try {
                    commands.write("quit\n");
                    commands.flush();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroy();
                        process.waitFor(3, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    process.destroy();
                }
            }
            if (process != null) {
                FileUtil.delete(tmpDir());
            }
            process = null;
            ready = null;
        }
    }

    @Override
    public void dispose() {
        stop();
        FileUtil.delete(previewDir());
    }
}
