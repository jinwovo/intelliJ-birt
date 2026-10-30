package io.github.intellijbirt.idea;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseHandler;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 리포트 저장을 이클립스처럼 — Ctrl+S로만 저장, 저장 안 한 탭은 이름 앞에 *, 닫을 때 저장할지 물어봄.
 * IntelliJ 자동 저장(다른 프로그램으로 전환·잠시 쉼)은 "명시적이지 않은 저장"이라 리포트만 막음.
 * Ctrl+S와 프로젝트 닫기·IntelliJ 끄기 때 저장은 명시적 저장이라 그대로 (그 전에 저장 / 저장 안 함 / 취소를 물어봄).
 */
final class BirtSaving {

    private BirtSaving() {
    }

    static boolean isReport(@Nullable Document document) {
        VirtualFile file = document == null ? null : FileDocumentManager.getInstance().getFile(document);
        return file != null && BirtFileType.isReport(file);
    }

    private static boolean isOpenAnywhere(VirtualFile file) {
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (!p.isDisposed() && FileEditorManager.getInstance(p).isFileOpen(file)) {
                return true;
            }
        }
        return false;
    }

    /** 열려 있는 탭 이름(*)을 다시 */
    static void refreshTabs(VirtualFile file) {
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (!p.isDisposed() && FileEditorManager.getInstance(p).isFileOpen(file)) {
                FileEditorManagerEx.getInstanceEx(p).updateFilePresentation(file);
            }
        }
    }

    private static void refreshTabsLater(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file != null && BirtFileType.isReport(file)) {
            ApplicationManager.getApplication().invokeLater(() -> refreshTabs(file), ModalityState.nonModal());
        }
    }

    /**
     * 저장 / 저장 안 함 / 취소. 저장 안 함은 디스크 내용으로 되돌림, 취소(Esc 포함)는 그대로 둠.
     * @return 취소가 아니면 true
     */
    static boolean askSave(Project project, List<Document> documents) {
        StringBuilder names = new StringBuilder();
        for (Document d : documents) {
            VirtualFile f = FileDocumentManager.getInstance().getFile(d);
            names.append(names.length() == 0 ? "" : "\n").append(f == null ? "?" : f.getName());
        }
        String message = documents.size() == 1
                ? "'" + names + "'이(가) 수정되었습니다. 변경 내용을 저장할까요?"
                : "수정된 리포트가 있습니다. 변경 내용을 저장할까요?\n\n" + names;
        int answer = Messages.showYesNoCancelDialog(project, message, "변경 내용 저장",
                "저장", "저장 안 함", "취소", Messages.getQuestionIcon());
        if (answer == Messages.YES) {
            documents.forEach(d -> FileDocumentManager.getInstance().saveDocument(d));
        } else if (answer == Messages.NO) {
            documents.forEach(d -> FileDocumentManager.getInstance().reloadFromDisk(d));
        } else {
            return false;
        }
        return true;
    }

    /** 리포트는 자동 저장 안 함 */
    public static final class Vetoer extends FileDocumentSynchronizationVetoer {
        @Override
        public boolean maySaveDocument(@NotNull Document document, boolean isSaveExplicit) {
            return isSaveExplicit || !isReport(document);
        }
    }

    /** 저장 안 한 리포트 탭은 이름 앞에 * (IntelliJ 설정 "Mark modified (*)"가 켜져 있으면 IntelliJ가 붙임) */
    public static final class TabTitle implements EditorTabTitleProvider {
        @Override
        public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
            if (!BirtFileType.isReport(file) || UISettings.getInstance().getMarkModifiedTabsWithAsterisk()) {
                return null;
            }
            Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
            return doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc) ? "*" + file.getPresentableName() : null;
        }
    }

    /** 저장·디스크에서 다시 읽음 → 탭 이름의 * 정리 (저장은 저장 직전 알림에서 나중에 — 저장 뒤 알림은 2025.1에 없음) */
    public static final class SavedListener implements FileDocumentManagerListener {
        @Override
        public void beforeDocumentSaving(@NotNull Document document) {
            refreshTabsLater(document);
        }

        @Override
        public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
            refreshTabsLater(document);
        }

        @Override
        public void unsavedDocumentDropped(@NotNull Document document) {
            refreshTabsLater(document);
        }
    }

    /** 저장 안 한 리포트의 마지막 탭을 닫음 → 저장할지 물어봄 (IntelliJ는 탭 닫기를 취소할 수 없어서 닫은 뒤에) */
    public static final class ClosedListener implements FileEditorManagerListener {
        @Override
        public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
            if (!BirtFileType.isReport(file)) {
                return;
            }
            Project project = source.getProject();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || isOpenAnywhere(file)) {
                    return;
                }
                Document doc = FileDocumentManager.getInstance().getCachedDocument(file);
                if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
                    askSave(project, List.of(doc));
                }
            }, ModalityState.nonModal());
        }
    }

    /** 프로젝트 닫기·IntelliJ 끄기 전 — 저장 안 한 리포트가 있으면 저장 / 저장 안 함 / 취소(닫지 않음) */
    public static final class CloseHandler implements ProjectCloseHandler {
        @Override
        public boolean canClose(@NotNull Project project) {
            if (!ApplicationManager.getApplication().isDispatchThread() || ApplicationManager.getApplication().isHeadlessEnvironment()) {
                return true;   // 물어볼 수 없으면 IntelliJ 기본 (저장)
            }
            List<Document> documents = new ArrayList<>();
            for (Document d : FileDocumentManager.getInstance().getUnsavedDocuments()) {
                VirtualFile f = FileDocumentManager.getInstance().getFile(d);
                if (f != null && BirtFileType.isReport(f) && (FileEditorManager.getInstance(project).isFileOpen(f)
                        || ReadAction.compute(() -> ProjectFileIndex.getInstance(project).isInContent(f)))) {
                    documents.add(d);
                }
            }
            return documents.isEmpty() || askSave(project, documents);
        }
    }
}
