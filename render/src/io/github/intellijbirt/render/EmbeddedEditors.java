package io.github.intellijbirt.render;

import org.eclipse.birt.report.designer.internal.ui.editors.ReportEditorInput;
import org.eclipse.birt.report.designer.ui.editors.MultiPageReportEditor;
import org.eclipse.birt.report.designer.ui.editors.pages.ReportScriptFormPage;
import org.eclipse.birt.report.model.api.ModuleHandle;
import org.eclipse.birt.report.model.api.activity.ActivityStackListener;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.GraphicalViewer;
import org.eclipse.gef.ui.actions.ActionRegistry;
import org.eclipse.gef.ui.actions.UpdateAction;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.internal.win32.MONITORINFO;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.internal.win32.RECT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.MultiPageEditorPart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 실제 BIRT 리포트 편집기를 다른 프로그램(IntelliJ)의 창 안에 끼워 넣음.
 * 편집기는 숨긴 작업대 창에서 열고, 여러 페이지를 담은 부분(아래 탭 묶음)만 떼어 내 테두리 없는 창으로 옮긴 뒤
 * 그 창을 IntelliJ 쪽 창(hwnd)의 자식으로 붙임 — 이클립스의 파일 이름 탭·창 장식·아래 탭줄 없이 편집 화면만.
 * 같은 리포트는 편집기 하나 — IntelliJ의 Layout·Master Page 탭이 바뀌면 그쪽 창으로 옮겨 붙이고 페이지를 바꿈.
 * <p>
 * 동기화: 편집기에서 바꿀 때마다(되돌리기·다시하기 포함) 모델을 이클립스 저장 형식 그대로 임시 파일에 쓰고
 * "@@ event changed 키 파일" 한 줄로 알림 → IntelliJ가 문서에 반영. 반대로 IntelliJ에서 XML을 고치면 reload로 다시 읽음.
 */
final class EmbeddedEditors {

    private static final String EDITOR_ID = "org.eclipse.birt.report.designer.ui.editors.ReportEditor";
    private static final String PAGE_PREFIX = "org.eclipse.birt.report.designer.ui.editors.";

    private static final class Session {
        IEditorPart part;     // 작업대에 열린 것 (BIRT IDE 번들의 대리 편집기)
        IEditorPart editor;   // 실제 여러 페이지 편집기
        Shell shell;
        ModuleHandle model;
        ActivityStackListener listener;
        Runnable sync;
        File syncFile;
        // 붙어 있는 곳 — reload 뒤 그대로 다시 붙이려고
        boolean attached;
        long hwnd;
        String page = "layout";
        int width;
        int height;
    }

    private static final Map<String, Session> SESSIONS = new HashMap<>();

    private EmbeddedEditors() {
    }

    private static boolean keysInstalled;

    /**
     * 편집기 안 Ctrl 단축키 — 이클립스에서는 작업대가 처리하지만 다른 프로그램(IntelliJ) 안에 끼운 창에는 닿지 않아서 직접 처리.
     * <ul>
     *   <li>Ctrl+S: 이클립스가 따로 저장하지 않고 IntelliJ에 "저장" 알림 (저장은 IntelliJ 한 곳에서만: 두 쪽이 따로 저장하면
     *       서로 "파일이 밖에서 바뀜"이 됨). 편집 내용은 이미 IntelliJ 문서에 반영돼 있음</li>
     *   <li>Ctrl+Z·Ctrl+Y(Ctrl+Shift+Z)·Ctrl+C·Ctrl+X·Ctrl+V·Ctrl+A: 보이는 페이지의 동작(이클립스 Edit 메뉴와 같은 것)을 실행.
     *       글자 입력 칸 안에서는 그 칸이 처리하게 둠</li>
     * </ul>
     * 작업대가 먼저 처리한 키는 여기까지 오지 않음 (처리하면 이벤트 종류를 없앰).
     */
    private static void installKeys(Display display) {
        if (keysInstalled) {
            return;
        }
        keysInstalled = true;
        display.addFilter(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) == 0 || !(e.widget instanceof Control)) {
                return;
            }
            Shell shell = ((Control) e.widget).getShell();
            for (Map.Entry<String, Session> en : SESSIONS.entrySet()) {
                Session s = en.getValue();
                if (s.attached && s.shell == shell) {
                    if (handleKey(en.getKey(), s, e)) {
                        e.doit = false;
                        e.type = SWT.None;
                    }
                    return;
                }
            }
            // 보조 창(Property Editor 등) 안 — 저장·되돌리기·다시하기만 보고 있는 편집기로
            if (EmbeddedViews.isViewShell(shell) && (e.keyCode == 's' || e.keyCode == 'z' || e.keyCode == 'y')) {
                Map.Entry<String, Session> active = activeSession();
                if (active != null && handleKey(active.getKey(), active.getValue(), e)) {
                    e.doit = false;
                    e.type = SWT.None;
                }
            }
        });
    }

    /** 보고 있는 편집기 — 마지막으로 쓴 IntelliJ 창에 붙은 것, 없으면 붙어 있는 아무거나 */
    private static Map.Entry<String, Session> activeSession() {
        Map.Entry<String, Session> any = null;
        for (Map.Entry<String, Session> en : SESSIONS.entrySet()) {
            if (en.getValue().attached) {
                if (en.getValue().hwnd == lastHwnd) {
                    return en;
                }
                any = en;
            }
        }
        return any;
    }

    private static boolean dialogPlacementInstalled;
    /** 마지막으로 쓴 편집기가 붙은 IntelliJ 쪽 창 */
    private static long lastHwnd;

    /**
     * 편집기에서 여는 대화상자(데이터 바인딩 편집 등) — 이클립스는 작업대 창 가운데에 띄우지만 여기서는 작업대 창이 화면 밖에
     * 숨어 있어 주 모니터 왼쪽 위로 밀려남. 보이기 직전에 편집기가 붙은 IntelliJ 창 가운데로 옮기고(이클립스와 같은 자리 계산)
     * 그 창을 주인으로 삼아 IntelliJ 앞에 머물게 함. 대화상자에서 연 대화상자(부모가 대화상자)는 원래 자리 그대로.
     */
    private static void installDialogPlacement(Display display) {
        if (dialogPlacementInstalled) {
            return;
        }
        dialogPlacementInstalled = true;
        display.addFilter(SWT.MouseDown, e -> {
            if (e.widget instanceof Control) {
                Shell shell = ((Control) e.widget).getShell();
                for (Session s : SESSIONS.values()) {
                    if (s.attached && s.shell == shell) {
                        lastHwnd = s.hwnd;
                        return;
                    }
                }
            }
        });
        display.addFilter(SWT.Show, e -> {
            if (e.widget instanceof Shell && lastHwnd != 0) {
                placeDialog((Shell) e.widget);
            }
        });
    }

    private static void placeDialog(Shell dialog) {
        Composite parent = dialog.getParent();
        if ((parent != null && !isWorkbenchShell(parent)) || isWorkbenchShell(dialog)) {
            return;
        }
        for (Session s : SESSIONS.values()) {
            if (s.shell == dialog) {
                return;
            }
        }
        if (EmbeddedViews.isViewShell(dialog)) {
            return;
        }
        long owner = topLevel(lastHwnd);
        if (owner == 0) {
            return;
        }
        RECT o = new RECT();
        OS.GetWindowRect(owner, o);
        RECT r = new RECT();
        OS.GetWindowRect(dialog.handle, r);
        int w = r.right - r.left;
        int h = r.bottom - r.top;
        MONITORINFO mi = new MONITORINFO();
        mi.cbSize = MONITORINFO.sizeof;
        OS.GetMonitorInfo(OS.MonitorFromWindow(owner, OS.MONITOR_DEFAULTTONEAREST), mi);
        // JFace Window.getInitialLocation과 같게: 가로 가운데, 세로는 가운데에서 높이의 2/3 위
        int x = (o.left + o.right) / 2 - w / 2;
        int y = (o.top + o.bottom) / 2 - h * 2 / 3;
        x = Math.max(mi.rcWork_left, Math.min(x, mi.rcWork_right - w));
        y = Math.max(mi.rcWork_top, Math.min(y, mi.rcWork_bottom - h));
        OS.SetWindowLongPtr(dialog.handle, OS.GWLP_HWNDPARENT, owner);
        OS.SetWindowPos(dialog.handle, 0, x, y, 0, 0, OS.SWP_NOSIZE | OS.SWP_NOZORDER | OS.SWP_NOACTIVATE);
    }

    /** 자식 창이면 맨 위 창(IntelliJ 프레임)까지 */
    private static long topLevel(long hwnd) {
        long h = hwnd;
        while (h != 0 && (OS.GetWindowLongPtr(h, OS.GWL_STYLE) & OS.WS_CHILD) != 0) {
            h = OS.GetParent(h);
        }
        return h;
    }

    private static boolean isWorkbenchShell(Composite c) {
        for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            if (w.getShell() == c) {
                return true;
            }
        }
        return false;
    }

    /** @return 처리했으면 true */
    private static boolean handleKey(String key, Session s, Event e) {
        if (e.keyCode == 's') {
            // Script 페이지에서 고친 것을 먼저 모델에 넣고 바로 알림 → IntelliJ가 반영한 뒤 저장 (알림 순서대로 처리됨)
            commitScript(s);
            flushSync(key, s);
            App.OUT.println("@@ event\tsave\t" + key);
            return true;
        }
        if (e.widget instanceof StyledText && (e.keyCode == 'z' || e.keyCode == 'y')) {
            // Script 페이지 글자 편집의 되돌리기·다시하기 — 이클립스에서는 작업대 동작이 처리 (StyledText 자체엔 없음)
            IEditorPart script = scriptEditor(s);
            if (script == null) {
                return false;
            }
            boolean redo = e.keyCode == 'y' || (e.stateMask & SWT.SHIFT) != 0;
            textOperation(script, redo ? 2 : 1);   // ITextOperationTarget.REDO / UNDO
            return true;
        }
        if (e.widget instanceof Text || e.widget instanceof StyledText || e.widget instanceof Combo || e.widget instanceof CCombo) {
            return false;
        }
        boolean shift = (e.stateMask & SWT.SHIFT) != 0;
        String id;
        switch (e.keyCode) {
            case 'z':
                id = shift ? ActionFactory.REDO.getId() : ActionFactory.UNDO.getId();
                break;
            case 'y':
                id = ActionFactory.REDO.getId();
                break;
            case 'c':
                id = ActionFactory.COPY.getId();
                break;
            case 'x':
                id = ActionFactory.CUT.getId();
                break;
            case 'v':
                id = ActionFactory.PASTE.getId();
                break;
            case 'a':
                id = ActionFactory.SELECT_ALL.getId();
                break;
            default:
                return false;
        }
        IAction action = pageAction(s, id);
        if (action == null) {
            return false;
        }
        if (action.isEnabled()) {
            action.run();
        }
        return true;
    }

    /** 보이는 페이지의 동작 (undo·redo·copy·cut·paste·selectAll 등 ActionFactory id) — 실행 가능 상태는 지금 선택 기준으로 */
    private static IAction pageAction(Session s, String id) {
        IAction action = null;
        if (s.editor instanceof MultiPageReportEditor) {
            Object page = ((MultiPageReportEditor) s.editor).getActivePageInstance();
            if (page instanceof IAdaptable) {
                ActionRegistry registry = ((IAdaptable) page).getAdapter(ActionRegistry.class);
                if (registry != null) {
                    action = registry.getAction(id);
                }
            }
        }
        if (action == null) {
            action = s.editor.getEditorSite().getActionBars().getGlobalActionHandler(id);
        }
        if (action instanceof UpdateAction) {
            ((UpdateAction) action).update();
        }
        return action;
    }

    /** 보이는 페이지가 Script면 그 안의 스크립트 편집기 (아니면 null) */
    private static IEditorPart scriptEditor(Session s) {
        if (s.editor instanceof MultiPageReportEditor) {
            Object page = ((MultiPageReportEditor) s.editor).getActivePageInstance();
            if (page instanceof ReportScriptFormPage) {
                return ((ReportScriptFormPage) page).getScriptEditor();
            }
        }
        return null;
    }

    /**
     * Script 페이지에서 고친 스크립트를 모델에 넣음 — 이클립스는 페이지를 떠날 때(canLeaveThePage) 넣는데,
     * 여기서는 IntelliJ 탭을 바꿔도 BIRT 페이지는 그대로라 떼어 낼 때·Ctrl+S 때 직접 (스크립트 편집기의 doSave는 모델에만 넣음, 파일 저장 아님)
     */
    static boolean commitScript(Session s) {
        IEditorPart script = scriptEditor(s);
        if (script != null && script.isDirty()) {
            script.doSave(null);
            return true;
        }
        return false;
    }

    /** 스크립트 편집기(JSEditor)의 글자 동작 — 1 되돌리기, 2 다시하기 (ITextOperationTarget) */
    private static void textOperation(IEditorPart script, int operation) {
        try {
            Object viewer = script.getClass().getMethod("getViewer").invoke(script);
            if ((Boolean) viewer.getClass().getMethod("canDoOperation", int.class).invoke(viewer, operation)) {
                viewer.getClass().getMethod("doOperation", int.class).invoke(viewer, operation);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** 모아 두던 동기화를 지금 바로 */
    private static void flushSync(String key, Session s) {
        if (s.sync != null) {
            Display.getCurrent().timerExec(-1, s.sync);
            sync(key, s);
        }
    }

    /** 단축키와 같은 동작을 명령으로 (시험용) — @return 찾음·실행 */
    static String runAction(String key, String id) {
        IAction action = pageAction(session(key), id);
        if (action == null) {
            return "none";
        }
        if (!action.isEnabled()) {
            return "disabled";
        }
        action.run();
        return "run";
    }

    /** 편집기 열기 (이미 열려 있으면 그대로) */
    static void open(final String key, String design) throws Exception {
        if (SESSIONS.containsKey(key)) {
            return;
        }
        installKeys(Display.getCurrent());
        installDialogPlacement(Display.getCurrent());
        IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
        IEditorPart part = page.openEditor(new ReportEditorInput(new File(design)), EDITOR_ID, true);
        IEditorPart editor = unwrap(part);
        Shell shell = new Shell(Display.getCurrent(), SWT.NO_TRIM | SWT.TOOL);
        shell.setLayout(new FillLayout());
        Composite container = container(editor);
        container.setParent(shell);
        if (container instanceof CTabFolder) {
            // 아래 탭줄 숨김 — 페이지는 IntelliJ 탭으로 바꿈
            ((CTabFolder) container).setTabHeight(0);
        }
        App.hide(shell);
        final Session s = new Session();
        s.part = part;
        s.editor = editor;
        s.shell = shell;
        // 서버 임시 폴더(IntelliJ가 서버를 띄울 때·끌 때 지움), 편집기를 닫으면 바로 지움
        s.syncFile = new File(System.getProperty("java.io.tmpdir"),
                "sync-" + Integer.toHexString(key.hashCode()) + ".rptdesign");
        if (editor instanceof MultiPageReportEditor) {
            s.model = ((MultiPageReportEditor) editor).getModel();
        }
        if (s.model != null) {
            // 편집기에서 바뀔 때마다 잠깐 모았다가(끌기 등 연속 동작) 한 번 알림
            s.sync = () -> sync(key, s);
            s.listener = event -> {
                Display d = Display.getCurrent();
                d.timerExec(-1, s.sync);
                d.timerExec(150, s.sync);
            };
            s.model.getCommandStack().addListener(s.listener);
        }
        SESSIONS.put(key, s);
    }

    /** 모델을 이클립스 저장 형식 그대로 임시 파일에 쓰고 알림 */
    private static void sync(String key, Session s) {
        if (SESSIONS.get(key) != s || s.model == null) {
            return;
        }
        try (OutputStream out = new FileOutputStream(s.syncFile)) {
            s.model.serialize(out);
        } catch (Exception e) {
            e.printStackTrace();
            App.OUT.println("@@ event\terror\t" + key + "\t" + String.valueOf(e).replace('\t', ' ').replace('\n', ' '));
            return;
        }
        App.OUT.println("@@ event\tchanged\t" + key + "\t" + s.syncFile.getPath());
    }

    /** IntelliJ 창(hwnd) 안에 붙이고 페이지 선택 (layout | masterpage | script) */
    static void attach(String key, long hwnd, String page) {
        Session s = session(key);
        s.attached = true;
        s.hwnd = hwnd;
        lastHwnd = hwnd;
        s.page = page;
        if (s.editor instanceof MultiPageReportEditor) {
            ((MultiPageReportEditor) s.editor).setActivePage(PAGE_PREFIX + page);
        }
        // IntelliJ가 파일을 저장해서 수정 시각이 바뀌어도 "밖에서 바뀜, 다시 읽을까요?" 창이 뜨지 않게 (숨은 창에서 멈춤)
        syncModificationStamp(s);
        // 활성 편집기 — 보조 창(Property Editor 등)이 이 편집기를 따라감
        PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage().activate(s.part);
        embed(s.shell, hwnd);
    }

    /** IntelliJ 창에서 떼어 냄 (탭이 숨거나 창이 없어지기 전에) — 숨긴 채로 보관 */
    static void detach(String key) {
        Session s = SESSIONS.get(key);
        if (s == null || s.shell.isDisposed()) {
            return;
        }
        s.attached = false;
        // Script 탭을 떠남 — 이클립스가 페이지를 떠날 때처럼 스크립트를 모델에 넣고 바로 알림 (탭을 닫는 중이어도 남게)
        if (commitScript(s)) {
            flushSync(key, s);
        }
        unembed(s.shell);
    }

    /** 편집기 페이지를 PNG로 (시험용, 화면에 띄우지 않음) */
    static void shot(String key, String page, int w, int h, String png) throws Exception {
        Session s = session(key);
        if (s.editor instanceof MultiPageReportEditor) {
            ((MultiPageReportEditor) s.editor).setActivePage(PAGE_PREFIX + page);
        }
        s.page = page;
        s.shell.setSize(w, h);
        s.shell.layout(true, true);
        Composite content = container(s.editor);
        org.eclipse.swt.graphics.Image image = new org.eclipse.swt.graphics.Image(Display.getCurrent(), w, h);
        org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(image);
        try {
            content.print(gc);
        } finally {
            gc.dispose();
        }
        org.eclipse.swt.graphics.ImageLoader loader = new org.eclipse.swt.graphics.ImageLoader();
        loader.data = new org.eclipse.swt.graphics.ImageData[] {image.getImageData()};
        loader.save(png, SWT.IMAGE_PNG);
        image.dispose();
    }

    /** Script 페이지 글자 입력 (시험용 — 사람이 친 것처럼 글자 칸에) @return 입력 뒤 편집기 수정 표시 */
    static boolean setScriptText(String key, String text) throws Exception {
        IEditorPart script = scriptEditor(session(key));
        if (script == null) {
            throw new IllegalStateException("Script 페이지가 아님");
        }
        Object viewer = script.getClass().getMethod("getViewer").invoke(script);
        StyledText widget = (StyledText) viewer.getClass().getMethod("getTextWidget").invoke(viewer);
        widget.replaceTextRange(0, widget.getCharCount(), text);
        return script.isDirty();
    }

    /** 테두리 없는 창을 상대 창(hwnd)의 자식으로 붙이고 그 크기로 */
    static void embed(Shell shell, long hwnd) {
        long h = shell.handle;
        long style = OS.GetWindowLongPtr(h, OS.GWL_STYLE);
        OS.SetWindowLongPtr(h, OS.GWL_STYLE, (style & ~OS.WS_POPUP) | OS.WS_CHILD | OS.WS_CLIPCHILDREN);
        OS.SetParent(h, hwnd);
        RECT r = new RECT();
        OS.GetClientRect(hwnd, r);
        shell.setAlpha(255);
        shell.setVisible(true);
        OS.SetWindowPos(h, 0, 0, 0, r.right - r.left, r.bottom - r.top, OS.SWP_NOZORDER | 0x0020 /* SWP_FRAMECHANGED */);
        shell.layout(true, true);
    }

    /** 상대 창에서 떼어 내 숨김 */
    static void unembed(Shell shell) {
        long h = shell.handle;
        shell.setVisible(false);
        OS.SetParent(h, 0);
        long style = OS.GetWindowLongPtr(h, OS.GWL_STYLE);
        OS.SetWindowLongPtr(h, OS.GWL_STYLE, (style & ~OS.WS_CHILD) | OS.WS_POPUP);
        App.hide(shell);
    }

    /** 요소 선택 (시험용) — 편집기에서 클릭한 것과 같음 */
    static void select(String key, long elementId) {
        Session s = session(key);
        org.eclipse.birt.report.model.api.DesignElementHandle e = s.model.getElementByID(elementId);
        Object page = ((MultiPageReportEditor) s.editor).getActivePageInstance();
        GraphicalViewer viewer = ((IAdaptable) page).getAdapter(GraphicalViewer.class);
        for (Object part : viewer.getEditPartRegistry().values()) {
            if (part instanceof EditPart && e != null && e.equals(((EditPart) part).getModel())) {
                viewer.select((EditPart) part);
                return;
            }
        }
        throw new IllegalArgumentException("편집 화면에 없는 요소: " + elementId);
    }

    static void resize(String key, int w, int h) {
        Session s = session(key);
        s.width = w;
        s.height = h;
        OS.SetWindowPos(s.shell.handle, 0, 0, 0, w, h, OS.SWP_NOZORDER | OS.SWP_NOACTIVATE);
        s.shell.layout(true, true);
    }

    /** 요소 속성 바꾸기 — 편집기 모델의 되돌리기 기록에 남음 (편집기에서 직접 바꾼 것과 같게 동기화됨) */
    static void setProperty(String key, long elementId, String name, String value) throws Exception {
        Session s = session(key);
        org.eclipse.birt.report.model.api.DesignElementHandle e = s.model.getElementByID(elementId);
        if (e == null) {
            throw new IllegalArgumentException("요소 없음: " + elementId);
        }
        e.setProperty(name, value.isEmpty() ? null : value);
    }

    /** IntelliJ에서 XML을 고쳐 저장한 뒤 — 편집기를 새로 열고, 붙어 있던 곳·페이지·크기 그대로 */
    static void reload(String key, String design) throws Exception {
        Session old = SESSIONS.get(key);
        if (old == null) {
            return;
        }
        boolean attached = old.attached;
        long hwnd = old.hwnd;
        String page = old.page;
        int w = old.width;
        int h = old.height;
        close(key);
        open(key, design);
        if (attached) {
            attach(key, hwnd, page);
            if (w > 0 && h > 0) {
                resize(key, w, h);
            }
        }
    }

    /** 편집기 닫기 (저장하지 않음 — 파일은 IntelliJ가 관리) */
    static void close(String key) {
        Session s = SESSIONS.get(key);
        if (s == null) {
            return;
        }
        detach(key);
        SESSIONS.remove(key);
        if (s.model != null && s.listener != null) {
            s.model.getCommandStack().removeListener(s.listener);
        }
        if (s.sync != null) {
            Display.getCurrent().timerExec(-1, s.sync);
        }
        try {
            s.part.getSite().getPage().closeEditor(s.part, false);
        } catch (RuntimeException e) {
            e.printStackTrace();   // 끼운 창이 이미 없어진 편집기 — 닫다가 실패해도 세션은 정리됨
        }
        if (!s.shell.isDisposed()) {
            s.shell.dispose();
        }
        if (s.syncFile != null) {
            s.syncFile.delete();
        }
    }

    /** 서버 끝낼 때 — 열린 편집기를 모두 저장하지 않고 닫음 (안 그러면 작업대가 "저장할까요?" 창을 띄움) */
    static void closeAll() {
        for (String key : new java.util.ArrayList<>(SESSIONS.keySet())) {
            try {
                close(key);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        for (org.eclipse.ui.IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            if (w.getActivePage() != null) {
                w.getActivePage().closeAllEditors(false);
            }
        }
    }

    private static Session session(String key) {
        Session s = SESSIONS.get(key);
        if (s != null && s.shell.isDisposed()) {
            // IntelliJ 쪽 창이 떼어 내기 전에 없어지면 끼워 둔 창도 같이 없어짐 — 편집기를 닫고 열리지 않은 것으로 (IntelliJ가 다시 엶)
            close(key);
            s = null;
        }
        if (s == null) {
            throw new IllegalStateException("열리지 않은 편집기: " + key);
        }
        return s;
    }

    /** 편집기가 기억하는 파일 수정 시각을 지금 파일 시각으로 */
    private static void syncModificationStamp(Session s) {
        if (!(s.editor instanceof MultiPageReportEditor) || s.model == null || s.model.getFileName() == null) {
            return;
        }
        try {
            Field f = MultiPageReportEditor.class.getDeclaredField("fModificationStamp");
            f.setAccessible(true);
            f.setLong(s.editor, new File(s.model.getFileName()).lastModified());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** BIRT IDE 번들의 대리 편집기(ReportEditorProxy)면 안의 실제 편집기 */
    private static IEditorPart unwrap(IEditorPart part) throws Exception {
        if (part instanceof MultiPageEditorPart) {
            return part;
        }
        Object inner = part.getClass().getMethod("getEditorPart").invoke(part);
        return inner instanceof IEditorPart ? (IEditorPart) inner : part;
    }

    /** 여러 페이지를 담은 부분 (MultiPageEditorPart의 탭 묶음) */
    private static Composite container(IEditorPart editor) throws Exception {
        Field f = MultiPageEditorPart.class.getDeclaredField("container");
        f.setAccessible(true);
        return (Composite) f.get(editor);
    }
}
