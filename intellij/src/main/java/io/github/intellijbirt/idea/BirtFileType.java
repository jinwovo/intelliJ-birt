package io.github.intellijbirt.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ide.highlighter.XmlLikeFileType;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** BIRT 리포트 파일 (rptdesign·rptlibrary·rpttemplate) — 내용은 XML이라 XML 편집 기능을 그대로 씀 */
public final class BirtFileType extends XmlLikeFileType {

    public static final BirtFileType INSTANCE = new BirtFileType();

    private BirtFileType() {
        super(XMLLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "BIRT Report";
    }

    @Override
    public @NotNull String getDescription() {
        return "BIRT 리포트 (rptdesign)";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "rptdesign";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Xml;
    }

    /** 검사·미리보기 대상 (rptdesign만 실행 가능 — 라이브러리·템플릿은 검사만) */
    public static boolean isReport(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && "rptdesign".equalsIgnoreCase(file.getExtension());
    }

    public static boolean isBirtFile(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && file.getFileType() == INSTANCE;
    }
}
