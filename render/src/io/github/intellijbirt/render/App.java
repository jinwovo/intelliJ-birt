package io.github.intellijbirt.render;

import org.eclipse.birt.report.designer.core.model.SessionHandleAdapter;
import org.eclipse.birt.report.designer.internal.ui.dialogs.ReportGraphicsViewPainter;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.MasterPageHandle;
import org.eclipse.birt.report.model.api.ModuleHandle;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.SWTGraphics;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.gef.GraphicalEditPart;
import org.eclipse.gef.GraphicalViewer;
import org.eclipse.gef.LayerConstants;
import org.eclipse.gef.editparts.LayerManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이클립스 BIRT 디자이너(4.8)의 Layout·Master Page 화면을 디자이너 코드 그대로 그리는 앱 — 이클립스와 픽셀까지 같음.
 * 창 없는 Workbench 안에서 BIRT의 ReportGraphicsViewPainter(템플릿 미리보기용)가 만든 화면(viewer)을 원래 크기로 PNG에 그리고,
 * 요소별 위치(XML id → 사각형)를 같은 이름의 .json으로 씀.
 *
 * <pre>
 * 상주 모드: 인자 --serve. 표준입력 한 줄에 명령 하나(탭 구분, UTF-8), 결과는 표준출력에 "@@ "로 시작하는 한 줄
 *   render  번호  리포트경로  내용경로  PNG경로  화면폭  layout|master   → @@ ok 번호 폭 높이 용지x 용지y 걸린ms | @@ error 번호 메시지
 *   open 번호 키 리포트경로 · attach 번호 키 hwnd layout|masterpage|script · detach 번호 키 · resize 번호 키 폭 높이
 *   reload 번호 키 리포트경로 · setprop 번호 키 요소id 속성 값 · action 번호 키 undo|redo|copy|… → none|disabled|run · close 번호 키
 *           → 실제 BIRT 편집기를 IntelliJ 창(hwnd) 안에 끼워 넣음 (EmbeddedEditors)
 *   알림(요청 없이): @@ event changed 키 파일 — 편집기에서 바뀐 내용(이클립스 저장 형식)을 쓴 파일
 *                   @@ event save 키 — 편집기 안에서 Ctrl+S (저장은 IntelliJ가)
 *   bundles 파일                                                     → 실제로 쓰인 번들 목록 (배포 묶음 만들 때)
 *   quit
 *   내용경로: 저장 전 편집 내용을 쓴 임시 파일 — 상대 경로(이미지 등)는 리포트경로 기준
 * 한 번 모드: 인자 "리포트 PNG" 쌍 (시험용)
 * </pre>
 */
public class App implements IApplication {

    static final PrintStream OUT = new PrintStream(new FileOutputStream(java.io.FileDescriptor.out), true);

    public Object start(IApplicationContext context) throws Exception {
        final String[] a = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        final boolean serve = Arrays.asList(a).contains("--serve");
        final Display display = PlatformUI.createDisplay();
        PlatformUI.createAndRunWorkbench(display, new WorkbenchAdvisor() {
            public String getInitialWindowPerspectiveId() {
                return "io.github.intellijbirt.render.perspective";   // 편집기 영역만
            }

            public boolean openWindows() {
                return true;   // 창은 열지 않음
            }

            // 그래도 작업대가 기본 창을 하나 만듦 — 작업 표시줄에 안 뜨는 1x1 창으로 화면 밖·투명하게 만들고 열리면 바로 숨김
            public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
                return new WorkbenchWindowAdvisor(configurer) {
                    public void preWindowOpen() {
                        IWorkbenchWindowConfigurer c = getWindowConfigurer();
                        c.setShellStyle(SWT.NO_TRIM | SWT.TOOL);
                        c.setInitialSize(new Point(1, 1));
                        c.setShowMenuBar(false);
                        c.setShowCoolBar(false);
                        c.setShowStatusLine(false);
                        c.setShowPerspectiveBar(false);
                        c.setShowProgressIndicator(false);
                    }

                    // BIRT 편집기가 메인 메뉴 "edit" 뒤에 자기 메뉴를 끼워 넣음 — 없으면 편집기가 안 열림. 창은 숨겨서 안 보임
                    //   Script 페이지(글자 편집기)는 Edit 메뉴 안 표준 자리(find.ext 등)에 찾기 동작을 넣음 — 이클립스 Edit 메뉴와 같은 자리를 둠
                    public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer abc) {
                        return new ActionBarAdvisor(abc) {
                            protected void fillMenuBar(IMenuManager bar) {
                                bar.add(new MenuManager("&File", IWorkbenchActionConstants.M_FILE));
                                MenuManager edit = new MenuManager("&Edit", IWorkbenchActionConstants.M_EDIT);
                                for (String group : new String[] {IWorkbenchActionConstants.EDIT_START, IWorkbenchActionConstants.UNDO_EXT,
                                        IWorkbenchActionConstants.CUT_EXT, IWorkbenchActionConstants.FIND_EXT,
                                        IWorkbenchActionConstants.ADD_EXT, IWorkbenchActionConstants.EDIT_END,
                                        IWorkbenchActionConstants.MB_ADDITIONS}) {
                                    edit.add(new GroupMarker(group));
                                }
                                bar.add(edit);
                                bar.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
                                bar.add(new MenuManager("&Window", IWorkbenchActionConstants.M_WINDOW));
                                bar.add(new MenuManager("&Help", IWorkbenchActionConstants.M_HELP));
                            }
                        };
                    }

                    public void postWindowCreate() {
                        hide(getWindowConfigurer().getWindow().getShell());
                    }

                    public void postWindowOpen() {
                        hide(getWindowConfigurer().getWindow().getShell());
                    }
                };
            }

            public void postStartup() {
                if (serve) {
                    serve(display);
                    return;
                }
                try {
                    for (int i = 0; i + 1 < a.length; i += 2) {
                        render(display, a[i], a[i], a[i + 1], 1200, false);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                PlatformUI.getWorkbench().close();
            }
        });
        return IApplication.EXIT_OK;
    }

    static void hide(Shell shell) {
        if (shell != null && !shell.isDisposed()) {
            shell.setLocation(-32000, -32000);
            shell.setAlpha(0);
            shell.setVisible(false);
        }
    }

    /** 표준입력을 읽는 스레드 — 그리기는 화면 스레드에서 */
    private static void serve(final Display display) {
        // 혹시 남은 창이 있으면 숨김 (작업대가 만든 기본 창 등)
        for (Shell s : display.getShells()) {
            hide(s);
        }
        int visible = 0;
        for (Shell s : display.getShells()) {
            visible += s.isVisible() ? 1 : 0;
        }
        OUT.println("@@ shells\t" + display.getShells().length + "\t" + visible);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                OUT.println("@@ ready");
                String line;
                while ((line = in.readLine()) != null && !"quit".equals(line.trim())) {
                    final String[] c = line.split("\t", -1);
                    display.syncExec(() -> handle(display, c));
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            // 열린 편집기를 저장 없이 먼저 닫음 — 안 그러면 작업대가 "저장할까요?" 창을 띄우고 멈춤
            display.asyncExec(() -> {
                EmbeddedEditors.closeAll();
                PlatformUI.getWorkbench().close();
            });
        }, "birt-render-commands");
        reader.setDaemon(true);
        reader.start();
    }

    private static java.util.List<String> splitHosts(String csv) {
        java.util.List<String> hosts = new java.util.ArrayList<>();
        for (String h : csv.split(",")) {
            if (!h.trim().isEmpty()) {
                hosts.add(h.trim());
            }
        }
        return hosts;
    }

    private static void handle(Display display, String[] c) {
        String id = c.length > 1 ? c[1] : "";
        try {
            // 실제 편집기 끼워 넣기: open 키 리포트 / attach 키 hwnd 페이지 / detach 키 / resize 키 폭 높이 / close 키
            switch (c[0]) {
                case "open":
                    EmbeddedEditors.open(c[2], c[3]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "attach":
                    EmbeddedEditors.attach(c[2], Long.parseLong(c[3]), c[4]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "detach":
                    EmbeddedEditors.detach(c[2]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "resize":
                    EmbeddedEditors.resize(c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]));
                    OUT.println("@@ ok\t" + id);
                    return;
                case "setprop":
                    EmbeddedEditors.setProperty(c[2], Long.parseLong(c[3]), c[4], c[5]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "reload":
                    EmbeddedEditors.reload(c[2], c[3]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "action":
                    OUT.println("@@ ok\t" + id + "\t" + EmbeddedEditors.runAction(c[2], c[3]));
                    return;
                case "driver": {
                    // JDBC 드라이버 클래스를 BIRT JDBC 번들로 불러 봄 (DB 접속 없음) — drivers 폴더 확인용
                    org.osgi.framework.Bundle jdbc = org.eclipse.core.runtime.Platform.getBundle("org.eclipse.birt.report.data.oda.jdbc");
                    Class<?> manager = jdbc.loadClass("org.eclipse.birt.report.data.oda.jdbc.JDBCDriverManager");
                    Object instance = manager.getMethod("getInstance").invoke(null);
                    manager.getMethod("loadAndRegisterDriver", String.class, java.util.Collection.class).invoke(instance, c[2], null);
                    OUT.println("@@ ok\t" + id + "\tloaded");
                    return;
                }
                case "cli": {
                    // cli 번호 validate|params|render 리포트 [옵션]... → @@ ok 번호 종료코드 JSON
                    //   검사·PDF 만들기는 오래 걸릴 수 있어 화면 스레드가 아닌 작업 스레드에서 (설계 화면이 멈추지 않게)
                    final String[] cliArgs = java.util.Arrays.copyOfRange(c, 2, c.length);
                    Thread worker = new Thread(() -> {
                        Thread.currentThread().setContextClassLoader(App.class.getClassLoader());
                        String[] r = io.github.intellijbirt.cli.BirtCli.serve(cliArgs);
                        OUT.println("@@ ok\t" + id + "\t" + r[0] + "\t" + r[1]);
                    }, "birt-cli-" + id);
                    worker.setDaemon(true);
                    worker.start();
                    return;
                }
                case "guard":
                    // guard 번호 허용주소들 차단주소들 (쉼표 구분) — 설계 화면 DB 접속 보호 (GuardedConnectionFactory)
                    GuardedConnectionFactory.setHosts(splitHosts(c[2]), splitHosts(c[3]));
                    OUT.println("@@ ok\t" + id);
                    return;
                case "connect": {
                    // connect 번호 드라이버클래스 주소 — 설계 화면과 같은 길로 접속해 봄 (시험용, 접속되면 바로 닫음)
                    java.sql.Connection conn = org.eclipse.birt.report.data.oda.jdbc.JDBCDriverManager.getInstance()
                            .getConnection(c[2], c[3], new java.util.Properties(), null);
                    conn.close();
                    OUT.println("@@ ok\t" + id + "\tconnected");
                    return;
                }
                case "eshot":
                    // eshot 번호 키 페이지 폭 높이 PNG — 편집기 페이지 그림 (시험용)
                    EmbeddedEditors.shot(c[2], c[3], Integer.parseInt(c[4]), Integer.parseInt(c[5]), c[6]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "scripttext":
                    // scripttext 번호 키 글자 — Script 페이지 글자 바꾸기 (시험용)
                    OUT.println("@@ ok\t" + id + "\tdirty=" + EmbeddedEditors.setScriptText(c[2], c[3]));
                    return;
                case "select":
                    EmbeddedEditors.select(c[2], Long.parseLong(c[3]));
                    OUT.println("@@ ok\t" + id);
                    return;
                // 보조 창: vattach 번호 보조창id hwnd / vdetach 번호 보조창id / vresize 번호 보조창id 폭 높이 / vshot 번호 보조창id 폭 높이 PNG
                case "vattach":
                    EmbeddedViews.attach(c[2], Long.parseLong(c[3]));
                    OUT.println("@@ ok\t" + id);
                    return;
                case "vdetach":
                    EmbeddedViews.detach(c[2]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "vresize":
                    EmbeddedViews.resize(c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]));
                    OUT.println("@@ ok\t" + id);
                    return;
                case "vshot":
                    EmbeddedViews.shot(c[2], Integer.parseInt(c[3]), Integer.parseInt(c[4]), c[5]);
                    OUT.println("@@ ok\t" + id);
                    return;
                case "close":
                    EmbeddedEditors.close(c[2]);
                    OUT.println("@@ ok\t" + id);
                    return;
                default:
                    break;
            }
            if ("render".equals(c[0]) && c.length >= 7) {
                long t = System.currentTimeMillis();
                int[] r = render(display, c[2], c[3], c[4], Integer.parseInt(c[5]), "master".equals(c[6]));
                OUT.println("@@ ok\t" + id + "\t" + r[0] + "\t" + r[1] + "\t" + r[2] + "\t" + r[3] + "\t" + (System.currentTimeMillis() - t));
            } else if ("bundles".equals(c[0]) && c.length >= 2) {
                listBundles(c[1]);
                OUT.println("@@ ok\tbundles");
            } else {
                OUT.println("@@ error\t" + id + "\t알 수 없는 명령: " + c[0]);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            OUT.println("@@ error\t" + id + "\t" + String.valueOf(t).replace('\n', ' ').replace('\t', ' '));
        }
    }

    /**
     * @param design   리포트 파일 경로 (상대 경로 이미지 등의 기준)
     * @param content  그릴 내용 (저장 전 편집 내용을 쓴 임시 파일, 저장된 그대로면 design과 같음)
     * @param viewport 화면 폭 — 이클립스처럼 용지를 이 폭 안에서 가운데 둠
     * @return {이미지 폭, 높이, 용지 x, 용지 y}
     */
    static int[] render(Display display, String design, String content, String png, int viewport, boolean master)
            throws Exception {
        ModuleHandle h;
        try (FileInputStream in = new FileInputStream(content)) {
            h = SessionHandleAdapter.getInstance().init(design, in);
        }
        // 디자이너 코드 일부(Master Page 배경 등)가 "지금 편집 중인 리포트"를 여기서 찾음
        SessionHandleAdapter.getInstance().setReportDesignHandle(h);
        Object model = h;
        if (master) {
            Iterator<?> it = h.getMasterPages().iterator();
            if (it.hasNext()) {
                model = it.next();
            }
        }
        ReportGraphicsViewPainter p = new ReportGraphicsViewPainter(model);
        Image img = null;
        try {
            Field f = ReportGraphicsViewPainter.class.getDeclaredField("viewer");
            f.setAccessible(true);
            GraphicalViewer viewer = (GraphicalViewer) f.get(p);
            IFigure layers = ((LayerManager) viewer.getEditPartRegistry().get(LayerManager.ID))
                    .getLayer(LayerConstants.PRINTABLE_LAYERS);
            viewer.getControl().setSize(Math.max(200, viewport), 20000);
            layers.getUpdateManager().performValidation();
            IFigure contents = ((GraphicalEditPart) viewer.getRootEditPart().getContents()).getFigure();
            Rectangle cb = contents.getBounds().getCopy();
            int w = Math.max(Math.max(200, viewport), cb.right() + 20);
            int hh = Math.max(1, cb.bottom() + 20);
            img = new Image(display, w, hh);
            GC gc = new GC(img);
            SWTGraphics g = new SWTGraphics(gc);
            layers.paint(g);
            g.dispose();
            gc.dispose();
            ImageLoader l = new ImageLoader();
            l.data = new ImageData[]{img.getImageData()};
            l.save(png, SWT.IMAGE_PNG);
            writeBounds(viewer, png.substring(0, png.length() - 4) + ".json");
            return new int[]{w, hh, cb.x, cb.y};
        } finally {
            p.dispose();
            if (img != null) {
                img.dispose();
            }
            h.close();
        }
    }

    /** 요소별 위치 — [{"id":XML id, "type":요소 종류, "x","y","w","h"}] (이미지 좌표) */
    private static void writeBounds(GraphicalViewer viewer, String file) throws Exception {
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write("[");
            boolean first = true;
            for (Object o : viewer.getEditPartRegistry().entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                if (e.getKey() instanceof DesignElementHandle && e.getValue() instanceof GraphicalEditPart) {
                    DesignElementHandle de = (DesignElementHandle) e.getKey();
                    IFigure fig = ((GraphicalEditPart) e.getValue()).getFigure();
                    Rectangle r = fig.getBounds().getCopy();
                    fig.translateToAbsolute(r);
                    out.write((first ? "" : ",\n") + "{\"id\":" + de.getID() + ",\"type\":\"" + de.getDefn().getName()
                            + "\",\"master\":" + (de instanceof MasterPageHandle)
                            + ",\"x\":" + r.x + ",\"y\":" + r.y + ",\"w\":" + r.width + ",\"h\":" + r.height + "}");
                    first = false;
                }
            }
            out.write("]\n");
        }
    }

    /**
     * 실제로 쓰인 번들 목록 — 이 번들 + OSGi 핵심(설치·서비스)에서 출발해 연결(wire)과 조각(fragment)을 따라감.
     * 한 줄: 이름,버전,위치,상태
     */
    static void listBundles(String file) throws Exception {
        BundleContext ctx = FrameworkUtil.getBundle(App.class).getBundleContext();
        Set<Bundle> need = new LinkedHashSet<>();
        Deque<Bundle> todo = new ArrayDeque<>();
        List<String> core = Arrays.asList("org.eclipse.equinox.simpleconfigurator",
                "org.eclipse.equinox.common", "org.eclipse.core.runtime", "org.eclipse.equinox.ds", "org.eclipse.equinox.event");
        for (Bundle b : ctx.getBundles()) {
            if (b == ctx.getBundle() || core.contains(b.getSymbolicName()) || b.getBundleId() == 0 || birtExtension(b)) {
                todo.add(b);
            }
        }
        while (!todo.isEmpty()) {
            Bundle b = todo.poll();
            if (!need.add(b)) {
                continue;
            }
            BundleWiring w = b.adapt(BundleWiring.class);
            if (w == null) {
                continue;
            }
            for (BundleWire wire : w.getRequiredWires(null)) {
                todo.add(wire.getProviderWiring().getBundle());
            }
            for (BundleWire wire : w.getProvidedWires(HostNamespace.HOST_NAMESPACE)) {
                todo.add(wire.getRequirerWiring().getBundle());
            }
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            for (Bundle b : need) {
                out.write(b.getSymbolicName() + "," + b.getVersion() + "," + b.getLocation() + ","
                        + (b.getState() == Bundle.ACTIVE ? "active" : "resolved") + "\n");
            }
        }
    }

    /**
     * BIRT 번들 중 편집 화면에 필요한 것 — 연결(wire)이 없어도 확장으로 끼어드는 것(팔레트의 차트·크로스탭 등)까지.
     * 이클립스 IDE용·문서·예제·웹 뷰어·디버거·테스트는 뺌, 풀리지 않은(RESOLVED 아닌) 번들도 뺌
     */
    private static boolean birtExtension(Bundle b) {
        String n = b.getSymbolicName();
        if (n == null || !n.startsWith("org.eclipse.birt") || b.getState() < Bundle.RESOLVED) {
            return false;
        }
        for (String skip : new String[]{".rcp", ".debug", ".viewer", ".preview.web", ".cshelp", ".doc", ".example",
                ".sample", ".test", ".editor.xml.wtp", ".integration.wtp", ".wtp."}) {
            if (n.contains(skip)) {
                return false;
            }
        }
        return true;
    }

    public void stop() {
    }
}
