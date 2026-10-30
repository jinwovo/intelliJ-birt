package io.github.intellijbirt.idea;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.TextEditorWithPreview;
import com.intellij.openapi.fileEditor.WeighedFileEditorProvider;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * rptdesign 편집기 — 이클립스 BIRT 디자이너처럼 아래 탭 Layout | Master Page | Script | XML Source.
 * IntelliJ는 편집기가 여럿이면 아래에 탭을 둠. 순서는 가중치(작을수록 앞), 처음 열면 맨 앞(Layout).
 * 기본 XML 편집기는 숨기고 XML Source 탭이 대신함 (오른쪽 위 버튼으로 코드만·분할·레이아웃만).
 */
public final class BirtEditorProviders {

    private BirtEditorProviders() {
    }

    abstract static class Base extends WeighedFileEditorProvider implements DumbAware {
        private final String id;
        private final double weight;

        Base(String id, double weight) {
            this.id = id;
            this.weight = weight;
        }

        @Override
        public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
            return BirtFileType.isReport(file);
        }

        @Override
        public @NotNull String getEditorTypeId() {
            return id;
        }

        @Override
        public double getWeight() {
            return weight;
        }

        @Override
        public @NotNull FileEditorPolicy getPolicy() {
            return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
        }
    }

    public static final class Layout extends Base {
        public Layout() {
            super("birt-layout", 1);
        }

        @Override
        public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            return new BirtEmbeddedEditor(project, file, "layout", "Layout");   // 이클립스 BIRT 실제 편집기
        }
    }

    public static final class MasterPage extends Base {
        public MasterPage() {
            super("birt-master-page", 2);
        }

        @Override
        public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            return new BirtEmbeddedEditor(project, file, "masterpage", "Master Page");
        }
    }

    public static final class Script extends Base {
        public Script() {
            super("birt-script", 3);
        }

        @Override
        public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            return new BirtEmbeddedEditor(project, file, "script", "Script");   // 이클립스 Script 페이지 (고른 요소의 스크립트)
        }
    }

    /** XML 코드 (구조 보기·검사·찾아가기는 여기서). 오른쪽 위 버튼으로 레이아웃과 나란히 볼 수 있음 */
    public static final class XmlSource extends Base {
        public XmlSource() {
            super("birt-xml-source", 4);
        }

        @Override
        public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
            TextEditor text = (TextEditor) TextEditorProvider.getInstance().createEditor(project, file);
            BirtLayoutPreviewEditor layout = new BirtLayoutPreviewEditor(project, file, BirtLayoutPreviewEditor.Page.LAYOUT, "split");
            return new TextEditorWithPreview(text, layout, "XML Source",
                    TextEditorWithPreview.Layout.SHOW_EDITOR, false, null);
        }
    }
}
