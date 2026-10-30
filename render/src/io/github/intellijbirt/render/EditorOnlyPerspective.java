package io.github.intellijbirt.render;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** 편집기 영역만 있는 화면 배치 — 보기(view) 없음 */
public class EditorOnlyPerspective implements IPerspectiveFactory {
    public void createInitialLayout(IPageLayout layout) {
        layout.setEditorAreaVisible(true);
    }
}
