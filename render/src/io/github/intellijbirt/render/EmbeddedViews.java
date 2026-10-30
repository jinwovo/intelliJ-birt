package io.github.intellijbirt.render;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.PageBookView;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * BIRT 보조 창(Property Editor 등)을 IntelliJ 도구 창 안에 끼워 넣음 — 편집기와 같은 방식.
 * 숨긴 작업대 창에서 보조 창을 열고, 그 안의 페이지 묶음(PageBook: 편집기마다 페이지를 바꿔 보여 주는 부분)만 떼어 내
 * 테두리 없는 창으로 옮긴 뒤 IntelliJ 쪽 창(hwnd)의 자식으로 붙임.
 * 보조 창은 활성 편집기를 따라가므로(편집기는 붙일 때마다 활성화) 편집기에서 요소를 고르면 그 요소의 속성이 보임.
 * 보조 창은 종류마다 하나 — 붙일 곳이 바뀌면 옮겨 붙임.
 */
final class EmbeddedViews {

    private static final class View {
        IViewPart part;
        Control book;
        Shell shell;
    }

    private static final Map<String, View> VIEWS = new HashMap<>();

    private EmbeddedViews() {
    }

    static boolean isViewShell(Shell shell) {
        for (View v : VIEWS.values()) {
            if (v.shell == shell) {
                return true;
            }
        }
        return false;
    }

    /** 보조 창 열기 (이미 열려 있으면 그대로) — 열어도 편집기가 계속 활성이게 */
    private static View view(String id) throws Exception {
        View v = VIEWS.get(id);
        if (v != null && !v.shell.isDisposed()) {
            return v;
        }
        IWorkbenchPage page = PlatformUI.getWorkbench().getWorkbenchWindows()[0].getActivePage();
        if (v != null) {
            // IntelliJ 쪽 창과 함께 없어진 보조 창 — 닫고 새로 엶
            VIEWS.remove(id);
            try {
                page.hideView(v.part);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
        IWorkbenchPart active = page.getActivePart();
        IViewPart part = page.showView(id, null, IWorkbenchPage.VIEW_VISIBLE);
        if (active != null && active != part) {
            page.activate(active);
        }
        Method m = PageBookView.class.getDeclaredMethod("getPageBook");
        m.setAccessible(true);
        Control book = (Control) m.invoke(part);
        Shell shell = new Shell(Display.getCurrent(), SWT.NO_TRIM | SWT.TOOL);
        shell.setLayout(new FillLayout());
        v = new View();
        v.part = part;
        v.book = book;
        v.shell = shell;
        VIEWS.put(id, v);   // 보이기 전에 등록 — 대화상자 자리 옮기기에서 빠지게
        book.setParent(shell);
        App.hide(shell);
        return v;
    }

    static void attach(String id, long hwnd) throws Exception {
        EmbeddedEditors.embed(view(id).shell, hwnd);
    }

    static void detach(String id) {
        View v = VIEWS.get(id);
        if (v != null && !v.shell.isDisposed()) {
            EmbeddedEditors.unembed(v.shell);
        }
    }

    static void resize(String id, int w, int h) {
        View v = VIEWS.get(id);
        if (v != null && !v.shell.isDisposed()) {
            OS.SetWindowPos(v.shell.handle, 0, 0, 0, w, h, OS.SWP_NOZORDER | OS.SWP_NOACTIVATE);
            v.shell.layout(true, true);
        }
    }

    /** 보조 창을 PNG로 (시험용, 화면에 띄우지 않음) */
    static void shot(String id, int w, int h, String png) throws Exception {
        View v = view(id);
        v.shell.setSize(w, h);
        v.shell.layout(true, true);
        Image image = new Image(Display.getCurrent(), w, h);
        GC gc = new GC(image);
        try {
            v.book.print(gc);
        } finally {
            gc.dispose();
        }
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {image.getImageData()};
        loader.save(png, SWT.IMAGE_PNG);
        image.dispose();
    }
}
