package io.github.intellijbirt.idea;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ComboboxWithBrowseButton;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import java.nio.file.Paths;
import java.util.Objects;

/** Settings > Tools > BIRT 4.8 — 전부 비워 둬도 동작 (IntelliJ 내장 Java + 플러그인 안의 BIRT 4.8) */
public final class BirtConfigurable implements Configurable {

    /** Java 목록 항목: home이 빈 값이면 IntelliJ 내장 Java */
    private record JavaChoice(String label, String home) {
    }

    private ComboBox<JavaChoice> java;
    private JBTextField allowHosts;
    private JBTextField denyHosts;
    private TextFieldWithBrowseButton driverJars;
    private TextFieldWithBrowseButton eclipseExe;

    @Override
    public @Nls String getDisplayName() {
        return "BIRT 4.8";
    }

    @Override
    public @Nullable JComponent createComponent() {
        java = new ComboBox<>();
        java.setRenderer(new SimpleListCellRenderer<JavaChoice>() {
            @Override
            public void customize(@NotNull JList<? extends JavaChoice> list, JavaChoice value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value.label());
            }
        });
        ComboboxWithBrowseButton javaBox = new ComboboxWithBrowseButton(java);
        javaBox.addActionListener(e -> chooseJavaFolder());

        allowHosts = new JBTextField();
        denyHosts = new JBTextField();
        driverJars = new TextFieldWithBrowseButton();
        driverJars.addActionListener(e -> addDriverJars());
        eclipseExe = new TextFieldWithBrowseButton();
        eclipseExe.addActionListener(e -> choose(eclipseExe, new FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("이클립스 실행 파일 (eclipse.exe)")));

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Java:", javaBox)
                .addComponentToRightColumn(hint("기본은 IntelliJ 내장 Java — 따로 설치할 필요 없음. 다른 Java로 돌려야 할 때만 목록이나 📁에서 선택"))
                .addLabeledComponent("미리보기 허용 DB:", allowHosts)
                .addComponentToRightColumn(hint("적으면 이 주소(호스트:포트)에 접속하는 리포트만 미리보기, 쉼표로 여러 개 — 예: 개발 DB devdb:8629"))
                .addLabeledComponent("미리보기 차단 DB:", denyHosts)
                .addComponentToRightColumn(hint("이 주소에 접속하는 리포트는 미리보기를 하지 않음 — 운영 DB 주소를 적어 두세요"))
                .addComponentToRightColumn(hint("리포트 위쪽 폴더의 " + BirtCliRunner.PROJECT_CONFIG
                        + " (preview.allowHosts / preview.denyHosts)도 함께 적용"))
                .addLabeledComponent("JDBC 드라이버 jar:", driverJars)
                .addComponentToRightColumn(hint("이클립스 Manage Drivers처럼 — 비우면 리포트 프로젝트의 WEB-INF/lib에서 찾음 (이클립스와 같음). 📁로 추가, 세미콜론 구분"))
                .addLabeledComponent("(선택) 이클립스:", eclipseExe)
                .addComponentToRightColumn(hint("화면 편집(끌어다 놓기)이 필요할 때만 — 지정하면 우클릭 메뉴에 '이클립스 BIRT 디자이너로 열기'가 생김"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private static JBLabel hint(String text) {
        JBLabel label = new JBLabel(text);
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }

    /** IntelliJ 내장 Java + IntelliJ에 등록된 JDK들 (+ 지금 설정값이 목록에 없으면 그것도) */
    private void fillJava(String selectedHome) {
        java.removeAllItems();
        java.addItem(new JavaChoice("IntelliJ 내장 Java (권장) — " + System.getProperty("java.version"), ""));
        for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
            String home = sdk.getHomePath();
            if (home != null && BirtCliRunner.javaExe(Paths.get(home)) != null) {
                java.addItem(new JavaChoice(sdk.getName() + "  (" + StringUtil.notNullize(sdk.getVersionString()) + ")  "
                        + FileUtil.toSystemDependentName(home), FileUtil.toSystemDependentName(home)));
            }
        }
        select(selectedHome);
    }

    private void select(String home) {
        for (int i = 0; i < java.getItemCount(); i++) {
            if (Objects.equals(java.getItemAt(i).home(), home)) {
                java.setSelectedIndex(i);
                return;
            }
        }
        JavaChoice custom = new JavaChoice(home, home);
        java.addItem(custom);
        java.setSelectedItem(custom);
    }

    /** 드라이버 jar 고르기 (여러 개) — 이미 적힌 것 뒤에 덧붙임 */
    private void addDriverJars() {
        VirtualFile[] jars = FileChooser.chooseFiles(new FileChooserDescriptor(true, false, true, true, false, true)
                .withExtensionFilter("jar").withTitle("JDBC 드라이버 jar"), null, null);
        StringBuilder text = new StringBuilder(driverJars.getText().trim());
        for (VirtualFile jar : jars) {
            String path = FileUtil.toSystemDependentName(VfsUtilCore.getVirtualFileForJar(jar) != null
                    ? VfsUtilCore.getVirtualFileForJar(jar).getPath() : jar.getPath());
            text.append(text.length() == 0 ? "" : ";").append(path);
        }
        driverJars.setText(text.toString());
    }

    private void chooseJavaFolder() {
        VirtualFile dir = FileChooser.chooseFile(new FileChooserDescriptor(false, true, false, false, false, false)
                .withTitle("Java 폴더 (bin\\java.exe가 있는 폴더)"), null, null);
        if (dir == null) {
            return;
        }
        String home = FileUtil.toSystemDependentName(dir.getPath());
        if (BirtCliRunner.javaExe(Paths.get(home)) == null) {
            Messages.showErrorDialog("이 폴더에 bin\\java.exe가 없습니다: " + home, "Java 선택");
            return;
        }
        select(home);
    }

    private static void choose(TextFieldWithBrowseButton field, FileChooserDescriptor descriptor) {
        String current = field.getText().trim();
        VirtualFile toSelect = current.isEmpty() ? null
                : LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(current));
        VirtualFile chosen = FileChooser.chooseFile(descriptor, null, toSelect);
        if (chosen != null) {
            field.setText(FileUtil.toSystemDependentName(chosen.getPath()));
        }
    }

    private String selectedJavaHome() {
        JavaChoice c = (JavaChoice) java.getSelectedItem();
        return c == null ? "" : c.home();
    }

    @Override
    public boolean isModified() {
        BirtSettings.State s = BirtSettings.getInstance().getState();
        return !Objects.equals(s.javaHome, selectedJavaHome())
                || !Objects.equals(s.allowHosts, allowHosts.getText().trim())
                || !Objects.equals(s.denyHosts, denyHosts.getText().trim())
                || !Objects.equals(s.driverJars, driverJars.getText().trim())
                || !Objects.equals(s.eclipseExe, eclipseExe.getText().trim());
    }

    @Override
    public void apply() {
        BirtSettings.State s = BirtSettings.getInstance().getState();
        s.javaHome = selectedJavaHome();
        s.allowHosts = allowHosts.getText().trim();
        s.denyHosts = denyHosts.getText().trim();
        s.driverJars = driverJars.getText().trim();
        s.eclipseExe = eclipseExe.getText().trim();
        BirtValidateAnnotator.clearCache();
    }

    @Override
    public void reset() {
        BirtSettings.State s = BirtSettings.getInstance().getState();
        fillJava(StringUtil.notNullize(s.javaHome));
        allowHosts.setText(s.allowHosts);
        denyHosts.setText(s.denyHosts);
        driverJars.setText(s.driverJars);
        eclipseExe.setText(s.eclipseExe);
    }
}
