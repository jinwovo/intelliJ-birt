package io.github.intellijbirt.idea;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 편집기·프로젝트 트리·Tools 메뉴의 BIRT 동작 */
public final class BirtActions {

    private static final Logger LOG = Logger.getInstance(BirtActions.class);

    private BirtActions() {
    }

    static void notify(@Nullable Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("BIRT Tools")
                .createNotification(title, content, type).notify(project);
    }

    /** 저장 안 한 변경이 있으면 먼저 저장 (birt-cli는 디스크 파일을 읽음) */
    static void save(VirtualFile file) {
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc != null) {
            FileDocumentManager.getInstance().saveDocument(doc);
        }
    }

    abstract static class Base extends AnAction implements DumbAware {
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            VirtualFile f = e.getData(CommonDataKeys.VIRTUAL_FILE);
            e.getPresentation().setEnabledAndVisible(e.getProject() != null && accepts(f));
        }

        boolean accepts(@Nullable VirtualFile f) {
            return BirtFileType.isReport(f);
        }
    }

    // ---------------------------------------------------------------- 검사

    public static final class Validate extends Base {
        @Override
        boolean accepts(@Nullable VirtualFile f) {
            return BirtFileType.isBirtFile(f);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (project == null || file == null) {
                return;
            }
            save(file);
            new Task.Backgroundable(project, "BIRT 검사: " + file.getName(), false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        BirtCliRunner.Result r = BirtCliRunner.run(file, List.of("validate", file.getPath()), 60);
                        report(project, file, r);
                    } catch (BirtCliRunner.SetupException ex) {
                        BirtActions.notify(project, "BIRT 검사", ex.getMessage(), NotificationType.ERROR);
                    }
                }
            }.queue();
        }

        private static void report(Project project, VirtualFile file, BirtCliRunner.Result r) {
            if (r.json == null || !r.json.has("errors")) {
                BirtActions.notify(project, "BIRT 검사 실패", r.error(), NotificationType.ERROR);
                return;
            }
            JsonArray errors = r.json.getAsJsonArray("errors");
            JsonArray warnings = r.json.getAsJsonArray("warnings");
            String version = BirtValidateAnnotator.str(r.json, "version");
            StringBuilder sb = new StringBuilder();
            sb.append("오류 ").append(errors.size()).append(" / 경고 ").append(warnings.size())
                    .append(" / 파일 형식 ").append(version == null ? "?" : version);
            if (version != null && !BirtValidateAnnotator.EXPECTED_VERSION.equals(version)) {
                sb.append(" (서버 BIRT 4.8은 ").append(BirtValidateAnnotator.EXPECTED_VERSION).append(")");
            }
            int shown = 0;
            for (JsonElement el : errors) {
                if (shown++ >= 5) {
                    break;
                }
                JsonObject o = el.getAsJsonObject();
                sb.append("<br>").append(o.get("line").getAsInt()).append("줄: ")
                        .append(StringUtil.escapeXmlEntities(
                                String.valueOf(BirtValidateAnnotator.str(o, "message"))));
            }
            BirtActions.notify(project, "BIRT 검사: " + file.getName(), sb.toString(),
                    errors.isEmpty() ? NotificationType.INFORMATION : NotificationType.ERROR);
        }
    }

    // ---------------------------------------------------------------- 미리보기

    abstract static class Preview extends Base {
        /** 정해진 형식 (null이면 실행할 때 고름) */
        private final @Nullable String format;

        Preview(@Nullable String format) {
            this.format = format;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (project == null || file == null) {
                return;
            }
            save(file);
            if (format != null) {
                start(project, file, format);
            } else {
                chooseFormat(project, file);
            }
        }

        /** 이 설치본의 BIRT 엔진이 만들 수 있는 형식 중 PDF·HTML 말고 (이클립스 Run > View Report 목록과 같음) */
        private void chooseFormat(Project project, VirtualFile file) {
            new Task.Backgroundable(project, "BIRT 출력 형식 읽기", false) {
                private BirtCliRunner.Result result;
                private String setupError;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        result = BirtCliRunner.run(file, List.of("formats"), 60);
                    } catch (BirtCliRunner.SetupException ex) {
                        setupError = ex.getMessage();
                    }
                }

                @Override
                public void onSuccess() {
                    if (setupError != null || !result.ok()) {
                        BirtActions.notify(project, "BIRT 미리보기", setupError != null ? setupError : result.error(), NotificationType.ERROR);
                        return;
                    }
                    Map<String, String> byLabel = new LinkedHashMap<>();
                    for (JsonElement f : result.json.getAsJsonArray("formats")) {
                        String id = f.getAsString();
                        if (!"pdf".equals(id) && !"html".equals(id)) {
                            byLabel.put(label(id), id);
                        }
                    }
                    if (byLabel.isEmpty()) {
                        BirtActions.notify(project, "BIRT 미리보기", "PDF·HTML 말고 만들 수 있는 형식이 없습니다", NotificationType.WARNING);
                        return;
                    }
                    JBPopupFactory.getInstance().createPopupChooserBuilder(new ArrayList<>(byLabel.keySet()))
                            .setTitle("BIRT 미리보기 — 출력 형식")
                            .setItemChosenCallback(label -> start(project, file, byLabel.get(label)))
                            .createPopup()
                            .showCenteredInCurrentWindow(project);
                }
            }.queue();
        }

        private static String label(String format) {
            switch (format) {
                case "doc":
                    return "DOC (Word 2003 XML)";
                case "docx":
                    return "DOCX (Word)";
                case "ppt":
                    return "PPT (PowerPoint)";
                case "pptx":
                    return "PPTX (PowerPoint)";
                case "odt":
                    return "ODT (OpenDocument 문서)";
                case "ods":
                    return "ODS (OpenDocument 스프레드시트)";
                case "odp":
                    return "ODP (OpenDocument 프레젠테이션)";
                case "postscript":
                    return "PostScript";
                default:
                    return format.toUpperCase();
            }
        }

        private static String extension(String format) {
            return "postscript".equals(format) ? "ps" : format;
        }

        private void start(Project project, VirtualFile file, String format) {
            // 1) 파라미터 목록 (백그라운드) → 2) 입력 창 (EDT) → 3) 실행 (백그라운드) → 4) 결과 열기
            new Task.Backgroundable(project, "BIRT 파라미터 읽기: " + file.getName(), false) {
                private BirtCliRunner.Result result;
                private String setupError;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        result = BirtCliRunner.run(file, List.of("params", file.getPath()), 60);
                    } catch (BirtCliRunner.SetupException ex) {
                        setupError = ex.getMessage();
                    }
                }

                @Override
                public void onSuccess() {
                    if (setupError != null) {
                        BirtActions.notify(project, "BIRT 미리보기", setupError, NotificationType.ERROR);
                        return;
                    }
                    if (!result.ok()) {
                        BirtActions.notify(project, "BIRT 미리보기 — 리포트를 열 수 없음", result.error(), NotificationType.ERROR);
                        return;
                    }
                    BirtParamDialog dialog = new BirtParamDialog(project, file.getPath(), file.getName(),
                            result.json.getAsJsonArray("params"), format);
                    if (dialog.showAndGet()) {
                        render(project, file, dialog.values(), format);
                    }
                }
            }.queue();
        }

        private void render(Project project, VirtualFile file, Map<String, String> values, String format) {
            Path dir = BirtDesignerServer.previewDir().toPath();
            File out = dir.resolve(FileUtil.getNameWithoutExtension(file.getName()) + "." + extension(format)).toFile();
            List<String> args = new ArrayList<>(List.of("render", file.getPath(), "--format", format, "--out", out.getPath()));
            for (Map.Entry<String, String> v : values.entrySet()) {
                args.add("--param");
                args.add(v.getKey() + "=" + v.getValue());
            }
            List<String> hostArgs = BirtCliRunner.hostArgs(file);
            if (hostArgs.isEmpty()) {
                // 허용·차단 목록이 하나도 없으면 접속할 DB를 보여주고 확인 (운영 DB에 그냥 붙지 않게)
                List<String> hosts = BirtCliRunner.dataSourceHosts(file);
                if (!hosts.isEmpty() && Messages.showYesNoDialog(project,
                        "이 리포트는 다음 DB에 접속합니다:\n  " + String.join("\n  ", hosts)
                                + "\n\n계속할까요? (Settings > Tools > BIRT 4.8 또는 " + BirtCliRunner.PROJECT_CONFIG
                                + "에 허용·차단 DB를 정해 두면 이 확인 없이 그 규칙대로 실행합니다)",
                        "BIRT 미리보기 — DB 확인", Messages.getWarningIcon()) != Messages.YES) {
                    return;
                }
            }
            args.addAll(hostArgs);
            new Task.Backgroundable(project, "BIRT 미리보기 (" + format.toUpperCase() + "): " + file.getName(), true) {
                private BirtCliRunner.Result result;
                private String setupError;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        result = BirtCliRunner.run(file, args, 300);
                    } catch (BirtCliRunner.SetupException ex) {
                        setupError = ex.getMessage();
                    }
                }

                @Override
                public void onSuccess() {
                    if (setupError != null) {
                        BirtActions.notify(project, "BIRT 미리보기", setupError, NotificationType.ERROR);
                    } else if (result.exitCode == 3) {
                        BirtActions.notify(project, "BIRT 미리보기 — 실행 안 함", result.error(), NotificationType.WARNING);
                    } else if (!result.ok()) {
                        String errors = result.json != null && result.json.has("errors")
                                ? result.json.get("errors").toString() : result.error();
                        BirtActions.notify(project, "BIRT 미리보기 실패", errors, NotificationType.ERROR);
                    } else {
                        open(project, file, out, result.json.has("errors") ? result.json.getAsJsonArray("errors") : new JsonArray(), format);
                    }
                }
            }.queue();
        }

        /** 결과 열기 — 리포트 스크립트 오류 등이 있어도 결과 파일이 있으면 열고 경고로 알림 (서버에서도 같은 결과) */
        private void open(Project project, VirtualFile file, File out, JsonArray warnings, String format) {
            String size = result(out);
            boolean inIde = false;
            if ("html".equals(format)) {
                // IDE 안 탭 (내장 브라우저). 이미지는 HTML 옆 폴더에서 상대 경로로 — 못 열면 기본 브라우저
                try {
                    HTMLEditorProvider.Companion.openEditor(project, "미리보기 " + file.getName(),
                            HTMLEditorProvider.Request.Companion.url(out.toURI().toString()));
                    inIde = true;
                } catch (RuntimeException | LinkageError e) {
                    LOG.info("IDE 안 HTML 탭을 열지 못해 브라우저로: " + e);
                }
            }
            if (!inIde) {
                BrowserUtil.browse(out);   // PDF 등: 그 형식의 기본 프로그램 (PDF 뷰어·Word·PowerPoint…)
            }
            if (warnings.isEmpty()) {
                BirtActions.notify(project, "BIRT 미리보기", file.getName() + " → " + out.getName() + size, NotificationType.INFORMATION);
            } else {
                StringBuilder sb = new StringBuilder(file.getName() + " → " + out.getName() + size
                        + "<br>리포트 실행 중 경고 " + warnings.size() + "건:");
                for (int i = 0; i < Math.min(3, warnings.size()); i++) {
                    sb.append("<br>• ").append(StringUtil.escapeXmlEntities(StringUtil.trimMiddle(warnings.get(i).getAsString(), 300)));
                }
                BirtActions.notify(project, "BIRT 미리보기 (경고 있음)", sb.toString(), NotificationType.WARNING);
            }
        }

        private static String result(File out) {
            return out.isFile() ? " (" + out.length() / 1024 + "KB)" : "";
        }
    }

    public static final class PreviewPdf extends Preview {
        public PreviewPdf() {
            super("pdf");
        }
    }

    public static final class PreviewHtml extends Preview {
        public PreviewHtml() {
            super("html");
        }
    }

    /** DOCX·PPTX·ODT 등 — 이클립스 Run > View Report의 나머지 형식 */
    public static final class PreviewOther extends Preview {
        public PreviewOther() {
            super(null);
        }
    }

    // ---------------------------------------------------------------- 이클립스 디자이너

    /** 화면 편집은 이클립스 BIRT 디자이너로 — 설정한 eclipse.exe에 파일을 넘김 (실행 중이면 그 창에서 열림) */
    public static final class OpenInEclipse extends Base {
        /** 이클립스를 설정한 경우에만 메뉴에 보임 (플러그인만 쓰는 사람에겐 안 보임) */
        @Override
        boolean accepts(@Nullable VirtualFile f) {
            String exe = BirtSettings.getInstance().getState().eclipseExe;
            return BirtFileType.isBirtFile(f) && exe != null && !exe.isEmpty();
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (project == null || file == null) {
                return;
            }
            String exe = BirtSettings.getInstance().getState().eclipseExe;
            if (exe == null || exe.isEmpty() || !new File(exe).isFile()) {
                BirtActions.notify(project, "이클립스 BIRT 디자이너",
                        "Settings > Tools > BIRT 4.8에서 이클립스 실행 파일(eclipse.exe)을 지정하세요", NotificationType.WARNING);
                return;
            }
            save(file);
            try {
                new GeneralCommandLine(exe, "--launcher.openFile", FileUtil.toSystemDependentName(file.getPath()))
                        .withWorkDirectory(new File(exe).getParentFile())
                        .createProcess();
            } catch (ExecutionException ex) {
                BirtActions.notify(project, "이클립스 BIRT 디자이너", "실행 실패: " + ex.getMessage(), NotificationType.ERROR);
            }
        }
    }
}
