package io.github.intellijbirt.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.sun.jna.Native;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Layout·Master Page 탭 — 이클립스 BIRT 디자이너 4.8의 실제 편집기(팔레트·눈금자·표 손잡이·우클릭 메뉴·끌어서 크기 조절 그대로)를
 * 설계 화면 서버(BirtDesignerServer)에서 열고, 그 창을 이 탭 안의 네이티브 영역(Canvas)에 끼워 넣음 (Windows 창 부모 바꾸기).
 * 같은 파일의 Layout·Master Page 탭은 편집기 하나를 같이 씀 — 보이는 탭이 붙여 가고 페이지를 바꿈.
 * 편집 내용은 BirtDesignerSync가 IntelliJ 문서와 주고받음 (저장은 IntelliJ가).
 * 탭이 숨거나 닫히면 먼저 떼어 냄 (부모 창이 없어질 때 같이 망가지지 않게).
 */
final class BirtEmbeddedEditor extends UserDataHolderBase implements FileEditor {

    private static final Logger LOG = Logger.getInstance(BirtEmbeddedEditor.class);

    /** 파일별 편집기 공유 상태 — 열린 탭 수, 지금 편집기를 붙이고 있는 탭 (EDT에서만 씀) */
    private static final Map<String, Integer> TABS = new HashMap<>();
    private static final Map<String, BirtEmbeddedEditor> OWNER = new HashMap<>();
    private static final Set<String> OPENED = new HashSet<>();
    private static final Map<String, BirtDesignerSync> SYNCS = new HashMap<>();
    private static final Set<String> PENDING_RELOAD = new HashSet<>();
    /** 파일별 서버에서 여는 중·연 결과 — 작업 스레드에서도 씀 */
    private static final Map<String, CompletableFuture<Void>> OPENING = new ConcurrentHashMap<>();

    private final Project project;
    private final VirtualFile file;
    private final String page;
    private final String title;
    private final String key;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JBLabel status = new JBLabel();
    private final Host host = new Host();
    private volatile boolean disposed;
    private boolean asking;

    /** @param page layout | masterpage */
    BirtEmbeddedEditor(Project project, VirtualFile file, String page, String title) {
        this.project = project;
        this.file = file;
        this.page = page;
        this.title = title;
        this.key = file.getPath();
        if (TABS.merge(key, 1, Integer::sum) == 1) {
            SYNCS.put(key, new BirtDesignerSync(project, file, key));   // 편집기 ↔ 문서 동기화 (파일당 하나)
        }
        BirtDesignerServer.getInstance().acquire();
        BirtViewWindows.designerOpened(project);

        status.setBorder(JBUI.Borders.empty(4, 8));
        status.setForeground(UIUtil.getContextHelpForeground());
        status.setText(title + " — 이클립스 BIRT 편집기를 여는 중… (처음 한 번은 몇 초 걸림)");
        panel.add(status, BorderLayout.NORTH);
        host.setBackground(new Color(0xF0F0F0));
        panel.add(host, BorderLayout.CENTER);

        host.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (host.isShowing()) {
                    attach();
                } else {
                    detach();
                }
            }
        });
        host.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (OWNER.get(key) == BirtEmbeddedEditor.this) {
                    send("resize", key, String.valueOf(deviceWidth()), String.valueOf(deviceHeight()));
                }
            }
        });
    }

    /** 디스크에서 다시 읽음(밖에서 바뀜) — 보이는 편집기는 바로, 아니면 다음에 붙일 때 다시 읽음 */
    static void diskChanged(String key) {
        if (!OPENED.contains(key)) {
            return;
        }
        BirtEmbeddedEditor owner = OWNER.get(key);
        if (owner == null) {
            PENDING_RELOAD.add(key);
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                BirtDesignerServer.getInstance().send("reload", key, owner.file.getPath()).get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.warn("BIRT 편집기 다시 읽기 실패", e);
            }
        });
    }

    /**
     * 이 탭 영역에 편집기를 붙임. 그 사이 XML Source에서 고쳤으면 이클립스처럼 저장할지 물어봄 —
     * 예: 저장하고 편집기가 다시 읽음, 아니요: 고친 것 버림, 취소: XML Source로 돌아감
     */
    private void attach() {
        if (disposed || asking || !host.isDisplayable()) {
            return;
        }
        BirtDesignerSync sync = SYNCS.get(key);
        if (sync == null || !sync.isChangedHere()) {
            attachNow(false);
            return;
        }
        asking = true;
        ApplicationManager.getApplication().invokeLater(() -> {   // 탭을 바꾸는 중이라 끝난 뒤에 물어봄
            asking = false;
            if (disposed || !host.isShowing()) {
                return;
            }
            int answer = Messages.showYesNoCancelDialog(project,
                    "XML 리포트 디자인 소스가 바뀌었습니다. 변경 내용을 저장할까요?", title,
                    "예", "아니요", "취소", Messages.getQuestionIcon());
            if (answer == Messages.YES) {
                sync.saveForReload();
                attachNow(true);
            } else if (answer == Messages.NO) {
                sync.revert();
                attachNow(false);
            } else {
                FileEditorManager.getInstance(project).setSelectedEditor(file, "birt-xml-source");
            }
        }, ModalityState.nonModal());
    }

    /** 처음이면 서버에서 편집기를 열고(디스크 파일), reload면 다시 읽고, 이 탭의 창에 붙임 */
    private void attachNow(boolean reload) {
        if (disposed || !host.isDisplayable()) {
            return;
        }
        long hwnd = Native.getComponentID(host);
        int w = deviceWidth();
        int h = deviceHeight();
        OWNER.put(key, this);
        boolean first = OPENED.add(key);
        boolean again = PENDING_RELOAD.remove(key) || reload;
        // 같은 파일의 다른 탭이 여는 중이면(서버 시작 포함 몇 초) 열린 뒤에 붙임 — 아니면 붙이기가 열기보다 먼저 갈 수 있음
        CompletableFuture<Void> opening = first ? new CompletableFuture<>() : OPENING.get(key);
        if (first) {
            OPENING.put(key, opening);
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            BirtDesignerServer server = BirtDesignerServer.getInstance();
            try {
                if (first) {
                    try {
                        openOnServer(server);
                        opening.complete(null);
                    } catch (Exception e) {
                        opening.completeExceptionally(e);
                        ApplicationManager.getApplication().invokeLater(() -> OPENED.remove(key), ModalityState.any());   // 다음에 다시 열게
                        throw e;
                    }
                } else {
                    if (opening != null) {
                        opening.get(150, TimeUnit.SECONDS);
                    }
                    if (again) {
                        server.send("reload", key, file.getPath()).get(120, TimeUnit.SECONDS);
                    }
                }
                if (disposed || OWNER.get(key) != this) {
                    return;
                }
                try {
                    server.send("attach", key, String.valueOf(hwnd), page).get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // 서버가 다시 시작됐거나 끼운 창이 없어져 편집기가 없음 — 다시 열고 붙임
                    if (e.getCause() == null || !String.valueOf(e.getCause().getMessage()).contains(NOT_OPENED)) {
                        throw e;
                    }
                    if (!readyToReopen()) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            status.setText(title + " — 설계 화면을 다시 열지 않았습니다. 파일을 닫았다 열면 다시 엽니다.");
                            status.setVisible(true);
                        }, ModalityState.any());
                        return;
                    }
                    openOnServer(server);
                    server.send("attach", key, String.valueOf(hwnd), page).get(30, TimeUnit.SECONDS);
                }
                server.send("resize", key, String.valueOf(w), String.valueOf(h)).get(30, TimeUnit.SECONDS);
                ApplicationManager.getApplication().invokeLater(() -> {
                    status.setVisible(false);
                    BirtViewWindows.designerAttached();
                }, ModalityState.any());
            } catch (Exception e) {
                LOG.warn("BIRT 편집기 끼워 넣기 실패", e);
                Throwable c = e.getCause() != null ? e.getCause() : e;
                ApplicationManager.getApplication().invokeLater(() -> {
                    status.setText(title + "을 열지 못했습니다: " + c.getMessage());
                    status.setVisible(true);
                }, ModalityState.any());
            }
        });
    }

    /** 서버가 이 파일의 편집기가 없다고 할 때의 메시지 (render EmbeddedEditors.session) */
    private static final String NOT_OPENED = "열리지 않은 편집기";

    /**
     * 편집기를 다시 열어도 되는지 — 편집기는 디스크 파일을 읽으므로 저장 안 한 내용이 있으면 이클립스처럼 저장할지 물어봄
     * (저장 안 함은 디스크 내용으로 되돌림, 취소는 다시 열지 않음). 작업 스레드에서 부름
     */
    private boolean readyToReopen() {
        Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
        if (doc == null || !FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            return true;
        }
        boolean[] ready = {false};
        ApplicationManager.getApplication().invokeAndWait(() -> {
            int answer = Messages.showYesNoCancelDialog(project,
                    "설계 화면을 다시 열어야 합니다. 저장하지 않은 변경 내용을 먼저 저장할까요?", title,
                    "저장", "저장 안 함", "취소", Messages.getQuestionIcon());
            if (answer == Messages.YES) {
                FileDocumentManager.getInstance().saveDocument(doc);
                ready[0] = true;
            } else if (answer == Messages.NO) {
                FileDocumentManager.getInstance().reloadFromDisk(doc);
                ready[0] = true;
            }
        }, ModalityState.nonModal());
        return ready[0];
    }

    /** 서버에서 편집기 열기 — 프로젝트 드라이버, DB 보호 목록(설계 화면 DB 접속도 미리보기와 같은 보호), 열기 */
    private void openOnServer(BirtDesignerServer server) throws Exception {
        BirtDesignerServer.installDrivers(file);
        String[] guard = BirtCliRunner.guardLists(file);
        server.send("guard", guard[0], guard[1]).get(30, TimeUnit.SECONDS);
        server.send("open", key, file.getPath()).get(120, TimeUnit.SECONDS);
    }

    /** 편집기를 이 탭에서 떼어 냄 — 탭이 숨거나 창(peer)이 없어지기 직전 (잠깐 기다림) */
    private void detach() {
        if (OWNER.get(key) != this) {
            return;
        }
        OWNER.remove(key);
        try {
            send("detach", key).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.info("BIRT 편집기 떼어 내기: " + e);
        }
    }

    private java.util.concurrent.CompletableFuture<String[]> send(String command, String... args) {
        return BirtDesignerServer.getInstance().send(command, args);
    }

    private int deviceWidth() {
        return (int) Math.round(host.getWidth() * JBUIScale.sysScale(host));
    }

    private int deviceHeight() {
        return (int) Math.round(host.getHeight() * JBUIScale.sysScale(host));
    }

    /** 끼워 넣을 네이티브 영역 — 없어지기 직전에 편집기를 떼어 냄 */
    private final class Host extends Canvas {
        @Override
        public void removeNotify() {
            detach();
            super.removeNotify();
        }
    }

    @Override
    public @NotNull JComponent getComponent() {
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return panel;
    }

    @Override
    public @NotNull String getName() {
        return title;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void dispose() {
        disposed = true;
        detach();
        int left = TABS.merge(key, -1, Integer::sum);
        if (left <= 0) {
            TABS.remove(key);
            BirtDesignerSync sync = SYNCS.remove(key);
            if (sync != null) {
                Disposer.dispose(sync);
            }
            PENDING_RELOAD.remove(key);
            OPENING.remove(key);
            if (OPENED.remove(key)) {
                send("close", key);   // 이 파일의 마지막 탭 — 편집기 닫기 (저장은 IntelliJ가)
            }
        }
        BirtViewWindows.designerClosed(project);
        BirtDesignerServer.getInstance().release();
    }

    /** 서버에 열린 편집기가 있는지 — 보조 창(Property Editor)은 편집기를 따라감 */
    static boolean anyOpened() {
        return !OPENED.isEmpty();
    }
}
