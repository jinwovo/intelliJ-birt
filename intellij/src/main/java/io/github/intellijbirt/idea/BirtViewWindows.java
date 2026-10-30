package io.github.intellijbirt.idea;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.sun.jna.Native;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 이클립스 BIRT 디자이너의 보조 창(Property Editor·Data Explorer)을 IntelliJ 도구 창으로 — 실제 BIRT 보조 창을 설계 화면 서버에서 열어
 * 도구 창 안의 네이티브 영역(Canvas)에 끼워 넣음 (편집기와 같은 방식). 보조 창은 설계 화면(Layout·Master Page)의 편집기를 따라감.
 * 고친 내용은 편집기 모델에 들어가므로 XML 반영·되돌리기·저장은 편집기와 같음.
 * rptdesign 설계 화면이 열려 있는 동안만 나타남. 서버에 보조 창은 종류마다 하나라 보이는 도구 창이 붙여 감.
 */
public abstract class BirtViewWindows implements ToolWindowFactory, DumbAware {

    private static final Logger LOG = Logger.getInstance(BirtViewWindows.class);

    /** Property Editor — 고른 요소의 속성 (아래) */
    public static final class PropertyEditor extends BirtViewWindows {
        public PropertyEditor() {
            super("org.eclipse.birt.report.designer.ui.attributes.AttributeView",
                    "rptdesign 파일의 Layout 탭을 열면 고른 요소의 속성이 여기에 보입니다.");
        }
    }

    /** Data Explorer — 데이터 소스·데이터셋·파라미터 (왼쪽 아래) */
    public static final class DataExplorer extends BirtViewWindows {
        public DataExplorer() {
            super("org.eclipse.birt.report.designer.ui.views.data.DataView",
                    "rptdesign 파일의 Layout 탭을 열면 데이터 소스·데이터셋·파라미터가 여기에 보입니다.");
        }
    }

    /** Outline — 리포트 구조(본문·마스터 페이지·스타일·스크립트…) 나무 (오른쪽) */
    public static final class Outline extends BirtViewWindows {
        public Outline() {
            super("org.eclipse.ui.views.ContentOutline",
                    "rptdesign 파일의 Layout 탭을 열면 리포트 구조가 여기에 보입니다.");
        }
    }

    /** Resource Explorer — 라이브러리·이미지 등 리소스 폴더 (오른쪽 아래) */
    public static final class ResourceExplorer extends BirtViewWindows {
        public ResourceExplorer() {
            super("org.eclipse.birt.report.designer.ui.lib.explorer.view",
                    "rptdesign 파일의 Layout 탭을 열면 라이브러리·리소스가 여기에 보입니다.");
        }
    }

    /** 도구 창 id (plugin.xml) */
    private static final String[] IDS = {"BIRT Property Editor", "BIRT Data Explorer", "BIRT Outline", "BIRT Resource Explorer"};
    /** 처음 한 번 펼쳐 둘 도구 창 (나머지는 사이드바 버튼만) */
    private static final List<String> SHOW_FIRST = List.of("BIRT Property Editor", "BIRT Data Explorer");

    /** 프로젝트별 설계 화면 탭 수, 도구 창 영역들, 보조 창별로 지금 붙이고 있는 곳 (EDT에서만 씀) */
    private static final Map<Project, Integer> DESIGNERS = new HashMap<>();
    private static final List<Host> HOSTS = new ArrayList<>();
    private static final Map<String, Host> OWNERS = new HashMap<>();

    private final String view;
    private final String emptyText;

    BirtViewWindows(String view, String emptyText) {
        this.view = view;
        this.emptyText = emptyText;
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return false;   // rptdesign 설계 화면을 열면 나타남
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        Host host = new Host(view, emptyText);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(host.status, BorderLayout.NORTH);
        panel.add(host, BorderLayout.CENTER);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(host);
        toolWindow.getContentManager().addContent(content);
        HOSTS.add(host);
    }

    /** 설계 화면 탭이 생김 — 프로젝트의 첫 탭이면 도구 창들을 보이게 (처음 한 번은 이클립스 화면처럼 펼쳐 둠) */
    static void designerOpened(Project project) {
        if (DESIGNERS.merge(project, 1, Integer::sum) != 1) {
            return;
        }
        ToolWindowManager.getInstance(project).invokeLater(() -> {
            if (!DESIGNERS.containsKey(project)) {
                return;
            }
            for (String id : IDS) {
                ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(id);
                if (tw == null) {
                    continue;
                }
                tw.setAvailable(true);
                String shownKey = "birt.toolWindow.shown." + id;
                PropertiesComponent props = PropertiesComponent.getInstance();
                if (SHOW_FIRST.contains(id) && !props.getBoolean(shownKey)) {
                    props.setValue(shownKey, true);
                    tw.show();
                }
            }
        });
    }

    /** 설계 화면 탭이 닫힘 — 프로젝트의 마지막 탭이면 도구 창들을 숨김 */
    static void designerClosed(Project project) {
        Integer left = DESIGNERS.merge(project, -1, Integer::sum);
        if (left != null && left > 0) {
            return;
        }
        DESIGNERS.remove(project);
        if (project.isDisposed()) {
            return;
        }
        for (String id : IDS) {
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(id);
            if (tw != null) {
                tw.setAvailable(false);
            }
        }
    }

    /** 설계 화면을 붙인 뒤 — 보이는데 아직 못 붙인 도구 창이 있으면 붙임 */
    static void designerAttached() {
        for (Host h : new ArrayList<>(HOSTS)) {
            if (h.isShowing() && OWNERS.get(h.view) != h) {
                h.attach();
            }
        }
    }

    /** 끼워 넣을 네이티브 영역 — 보이면 붙이고, 숨거나 없어지기 직전에 떼어 냄 */
    private static final class Host extends Canvas implements Disposable {
        final String view;
        final String emptyText;
        final JBLabel status = new JBLabel();

        Host(String view, String emptyText) {
            this.view = view;
            this.emptyText = emptyText;
            setBackground(new Color(0xF0F0F0));
            status.setBorder(JBUI.Borders.empty(4, 8));
            status.setForeground(UIUtil.getContextHelpForeground());
            status.setVisible(false);
            addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                    if (isShowing()) {
                        attach();
                    } else {
                        detach();
                    }
                }
            });
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (OWNERS.get(view) == Host.this) {
                        BirtDesignerServer.getInstance().send("vresize", view, String.valueOf(deviceWidth()), String.valueOf(deviceHeight()));
                    }
                }
            });
        }

        void attach() {
            if (!isDisplayable() || !isShowing()) {
                return;
            }
            if (!BirtEmbeddedEditor.anyOpened()) {
                showStatus(emptyText);
                return;
            }
            long hwnd = Native.getComponentID(this);
            int w = deviceWidth();
            int h = deviceHeight();
            OWNERS.put(view, this);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                BirtDesignerServer server = BirtDesignerServer.getInstance();
                try {
                    server.send("vattach", view, String.valueOf(hwnd)).get(60, TimeUnit.SECONDS);
                    server.send("vresize", view, String.valueOf(w), String.valueOf(h)).get(30, TimeUnit.SECONDS);
                    ApplicationManager.getApplication().invokeLater(() -> status.setVisible(false), ModalityState.any());
                } catch (Exception e) {
                    LOG.warn("BIRT 보조 창 끼워 넣기 실패: " + view, e);
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    ApplicationManager.getApplication().invokeLater(() -> showStatus("열지 못했습니다: " + c.getMessage()),
                            ModalityState.any());
                }
            });
        }

        void detach() {
            if (OWNERS.get(view) != this) {
                return;
            }
            OWNERS.remove(view);
            try {
                BirtDesignerServer.getInstance().send("vdetach", view).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.info("BIRT 보조 창 떼어 내기: " + e);
            }
        }

        private void showStatus(String text) {
            status.setText(text);
            status.setVisible(true);
        }

        private int deviceWidth() {
            return (int) Math.round(getWidth() * JBUIScale.sysScale(this));
        }

        private int deviceHeight() {
            return (int) Math.round(getHeight() * JBUIScale.sysScale(this));
        }

        @Override
        public void removeNotify() {
            detach();
            super.removeNotify();
        }

        @Override
        public void dispose() {
            HOSTS.remove(this);
            detach();
        }
    }
}
