package io.github.intellijbirt.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 리포트 파일 하나의 편집 동기화 — 이클립스 BIRT 편집기(설계 화면 서버) ↔ IntelliJ 문서.
 * <ul>
 *   <li>편집기에서 바꿈(되돌리기·다시하기 포함) → 서버가 이클립스 저장 형식 그대로 보낸 XML로 문서의 바뀐 구간만 교체.
 *       저장은 하지 않음 (Ctrl+S로만, BirtSaving), 바뀔 때마다 IntelliJ 되돌리기 한 단계</li>
 *   <li>편집기 안에서 Ctrl+S → IntelliJ가 문서 저장 (이클립스는 따로 저장하지 않음)</li>
 *   <li>XML Source에서 고침 → 다음에 Layout·Master Page 탭이 보일 때 이클립스처럼 저장할지 물어봄 (BirtEmbeddedEditor)</li>
 *   <li>디스크에서 다시 읽음(밖에서 바뀜, 저장 안 함) → 편집기도 다시 읽음</li>
 * </ul>
 */
final class BirtDesignerSync implements Disposable {

    private static final Logger LOG = Logger.getInstance(BirtDesignerSync.class);

    private final Project project;
    private final VirtualFile file;
    private final String key;
    private final @Nullable Document document;
    private boolean applying;
    private boolean changedHere;
    private boolean refreshScheduled;
    /** 편집기 모델 내용 (편집기에서 마지막으로 받은 것) — null이면 디스크 파일 그대로 */
    private @Nullable String designerText;
    private volatile boolean disposed;

    BirtDesignerSync(Project project, VirtualFile file, String key) {
        this.project = project;
        this.file = file;
        this.key = key;
        this.document = FileDocumentManager.getInstance().getDocument(file);
        // 저장 안 한 내용이 남아 있으면(닫을 때 취소 등) 디스크 파일을 읽는 편집기와 다름
        this.changedHere = document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document);
        BirtDesignerServer.getInstance().addEventListener(this, this::onEvent);
        if (document != null) {
            document.addDocumentListener(new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent event) {
                    if (!applying) {
                        changedHere = true;   // IntelliJ에서 고침 — 편집기가 아직 모름
                    }
                    refreshTabLater();
                }
            }, this);
        }
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
                    @Override
                    public void fileContentReloaded(@NotNull VirtualFile f, @NotNull Document d) {
                        if (d == document && !applying) {
                            changedHere = false;
                            designerText = null;
                            BirtEmbeddedEditor.diskChanged(key);
                        }
                    }
                });
    }

    /** XML Source 등에서 고쳐 편집기 내용과 달라졌는지 (고쳤다가 되돌려 같아졌으면 아님) */
    boolean isChangedHere() {
        if (!changedHere || document == null) {
            return false;
        }
        boolean same = designerText != null
                ? StringUtil.equals(designerText, document.getCharsSequence())
                : !FileDocumentManager.getInstance().isDocumentUnsaved(document);
        if (same) {
            changedHere = false;
        }
        return changedHere;
    }

    /** 고친 것 저장 — 편집기가 디스크 파일을 다시 읽게 */
    void saveForReload() {
        changedHere = false;
        designerText = null;
        if (document != null) {
            FileDocumentManager.getInstance().saveDocument(document);
        }
    }

    /** 고친 것 버림 — 편집기 내용으로 되돌림 */
    void revert() {
        if (document != null) {
            if (designerText == null) {
                applying = true;
                try {
                    FileDocumentManager.getInstance().reloadFromDisk(document);
                } finally {
                    applying = false;
                }
            } else {
                apply(designerText);
            }
        }
        changedHere = false;
    }

    /** 서버 알림 (서버 출력 스레드) — changed 키 파일 / save 키 */
    private void onEvent(String[] e) {
        if (disposed || e.length < 2 || !key.equals(e[1])) {
            return;
        }
        if ("changed".equals(e[0]) && e.length >= 3) {
            String text;
            try {
                text = StringUtil.convertLineSeparators(new String(Files.readAllBytes(Paths.get(e[2])), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                LOG.warn("BIRT 편집기 내용 읽기 실패: " + e[2], ex);
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> apply(text), ModalityState.nonModal());
        } else if ("save".equals(e[0])) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && document != null) {
                    FileDocumentManager.getInstance().saveDocument(document);
                }
            }, ModalityState.nonModal());
        }
    }

    /** 편집기에서 온 내용을 문서에 — 바뀐 구간만 (커서·접힘이 덜 흔들리게) */
    private void apply(String text) {
        if (disposed || document == null) {
            return;
        }
        designerText = text;
        if (StringUtil.equals(text, document.getCharsSequence())) {
            return;
        }
        applying = true;
        try {
            WriteCommandAction.runWriteCommandAction(project, "BIRT 디자이너 편집", null, () -> replaceChanged(document, text));
        } finally {
            applying = false;
        }
    }

    /** 탭 이름의 저장 안 함 표시(*) — 연달아 바뀌면 한 번만 */
    private void refreshTabLater() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            refreshScheduled = false;
            if (!disposed && !project.isDisposed()) {
                BirtSaving.refreshTabs(file);
            }
        }, ModalityState.nonModal());
    }

    /** 앞뒤로 같은 부분을 빼고 가운데만 교체 */
    static void replaceChanged(Document document, String text) {
        CharSequence old = document.getCharsSequence();
        int start = 0;
        int oldEnd = old.length();
        int newEnd = text.length();
        while (start < oldEnd && start < newEnd && old.charAt(start) == text.charAt(start)) {
            start++;
        }
        while (oldEnd > start && newEnd > start && old.charAt(oldEnd - 1) == text.charAt(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        document.replaceString(start, oldEnd, text.substring(start, newEnd));
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}
