package io.github.intellijbirt.idea;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.Alarm;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Layout·Master Page 탭 — 이클립스 BIRT 디자이너 4.8 코드로 그린 화면(BirtDesignerServer)을 그대로 보여 줌. 이클립스와 픽셀까지 같음.
 * 저장하지 않은 내용도 그림 (고치고 잠시 뒤 다시 그림), 화면에 보일 때만 그림. 이미지 픽셀은 화면 배율과 상관없이 1:1.
 */
final class BirtLayoutPreviewEditor extends UserDataHolderBase implements FileEditor {

    enum Page {
        LAYOUT("Layout"), MASTER("Master Page");

        final String title;

        Page(String title) {
            this.title = title;
        }
    }

    private final VirtualFile file;
    private final Page page;
    private final @Nullable Document document;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JBLabel status = new JBLabel();
    private final Canvas canvas = new Canvas();
    private final JBScrollPane scroll = new JBScrollPane(canvas);
    private final File out;
    private final File source;
    private final Alarm typing = new Alarm(this);
    private final AtomicBoolean rendering = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private boolean dirty = true;
    private volatile boolean disposed;

    /** @param slot 같은 파일의 화면끼리 임시 파일이 겹치지 않게 (Layout 탭, Master Page 탭, XML Source 분할 화면) */
    BirtLayoutPreviewEditor(Project project, VirtualFile file, Page page, String slot) {
        this.file = file;
        this.page = page;
        this.document = FileDocumentManager.getInstance().getDocument(file);
        File dir = Paths.get(BirtDesignerServer.previewDir().getPath(), "design",
                Integer.toHexString(file.getPath().hashCode())).toFile();
        String name = FileUtil.getNameWithoutExtension(file.getName()) + "." + slot;
        this.out = new File(dir, name + ".png");
        this.source = new File(dir, name + ".rptdesign");
        BirtDesignerServer.getInstance().acquire();   // 마지막 편집기가 닫히면 서버가 1분 뒤 종료

        status.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        status.setForeground(UIUtil.getContextHelpForeground());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)));
        bar.add(status);
        bar.add(new ActionLink("다시 그리기", (ActionListener) e -> render()));
        bar.add(new ActionLink("이미지 열기", (ActionListener) e -> {
            if (out.isFile()) {
                BrowserUtil.browse(out);
            }
        }));
        panel.add(bar, BorderLayout.NORTH);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        scroll.getHorizontalScrollBar().setUnitIncrement(JBUI.scale(16));
        panel.add(scroll, BorderLayout.CENTER);

        // 고치면(저장 전이라도) 잠시 뒤 다시 그림 — 다른 프로그램이 파일을 바꿔도 IntelliJ가 문서를 다시 읽으므로 여기로 옴
        if (document != null) {
            document.addDocumentListener(new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent event) {
                    typing.cancelAllRequests();
                    typing.addRequest(BirtLayoutPreviewEditor.this::render, 500);
                }
            }, this);
        }
        // 탭이 보이게 되면 밀린 것을 그림
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing() && dirty) {
                render();
            }
        });
        render();
    }

    /** 다시 그리기 — 안 보이는 탭이면 보일 때로 미룸, 그리는 중이면 끝난 뒤 한 번 더 (마지막 내용으로) */
    private void render() {
        if (disposed) {
            return;
        }
        if (!panel.isShowing()) {
            dirty = true;
            return;
        }
        dirty = false;
        if (!rendering.compareAndSet(false, true)) {
            pending.set(true);
            return;
        }
        int viewport = (int) Math.round(scroll.getViewport().getWidth() * JBUIScale.sysScale(panel));
        if (canvas.image == null) {
            setStatus(page.title + " 그리는 중… (처음 한 번은 설계 화면 서버를 띄우느라 몇 초 걸림)");
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                do {
                    pending.set(false);
                    renderOnce(viewport);
                } while (pending.get() && !disposed);
            } finally {
                rendering.set(false);
            }
        });
    }

    private void renderOnce(int viewport) {
        String message;
        BufferedImage image = null;
        try {
            // 편집기의 내용(저장 전 포함)을 임시 파일로 — 이미지 등 상대 경로는 원래 리포트 경로 기준
            File content = new File(file.getPath());
            String text = document == null ? null : ReadAction.compute(document::getText);
            if (text != null) {
                FileUtil.createParentDirs(source);
                Files.write(source.toPath(), text.getBytes(StandardCharsets.UTF_8));
                content = source;
            }
            FileUtil.createParentDirs(out);
            BirtDesignerServer.Rendered r = BirtDesignerServer.getInstance()
                    .render(new File(file.getPath()), content, out, Math.max(400, viewport), page == Page.MASTER)
                    .get();
            image = ImageIO.read(r.png);
            message = page.title + " — 이클립스 BIRT 디자이너 4.8과 같은 화면 · 고치면 바로 다시 그림 (" + r.millis + "ms)";
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            message = page.title + "을 그리지 못했습니다: " + c.getMessage();
        }
        BufferedImage shown = image;
        String text = message;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) {
                return;
            }
            setStatus(text);
            if (shown != null) {
                canvas.setImage(shown);
            }
        }, ModalityState.any());
    }

    private void setStatus(String text) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            status.setText(text);
        } else {
            ApplicationManager.getApplication().invokeLater(() -> status.setText(text), ModalityState.any());
        }
    }

    /** 그린 화면 — 이미지 픽셀을 화면 픽셀에 1:1로 (배율이 125%면 논리 크기를 1/1.25로) */
    private static final class Canvas extends JComponent {
        BufferedImage image;
        Color background = new Color(0xA3ABC9);

        void setImage(BufferedImage image) {
            this.image = image;
            background = new Color(image.getRGB(1, 1));   // 이클립스 편집기 바탕색 (그린 화면 구석)
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            if (image == null) {
                return new Dimension(0, 0);
            }
            double s = JBUIScale.sysScale(this);
            return new Dimension((int) Math.ceil(image.getWidth() / s), (int) Math.ceil(image.getHeight() / s));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(background);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (image != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                double s = JBUIScale.sysScale(this);
                g2.scale(1 / s, 1 / s);
                g2.drawImage(image, 0, 0, null);
                g2.dispose();
            }
        }
    }

    @Override
    public @NotNull JComponent getComponent() {
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return scroll;
    }

    @Override
    public @NotNull String getName() {
        return page.title;
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
        BirtDesignerServer.getInstance().release();
        // 이 화면의 그림·저장 전 내용 (요소 위치 파일 포함)
        FileUtil.delete(out);
        FileUtil.delete(source);
        FileUtil.delete(new File(out.getPath().substring(0, out.getPath().length() - 4) + ".json"));
    }
}
