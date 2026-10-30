package io.github.intellijbirt.cli;

import com.ibm.icu.util.ULocale;
import org.eclipse.birt.core.framework.Platform;
import org.eclipse.birt.report.engine.api.EngineConfig;
import org.eclipse.birt.report.engine.api.HTMLCompleteImageHandler;
import org.eclipse.birt.report.engine.api.HTMLRenderOption;
import org.eclipse.birt.report.engine.api.IRenderOption;
import org.eclipse.birt.report.engine.api.IReportEngine;
import org.eclipse.birt.report.engine.api.IReportEngineFactory;
import org.eclipse.birt.report.engine.api.IReportRunnable;
import org.eclipse.birt.report.engine.api.IRunAndRenderTask;
import org.eclipse.birt.report.engine.api.PDFRenderOption;
import org.eclipse.birt.report.engine.api.RenderOption;
import org.eclipse.birt.report.model.api.DesignConfig;
import org.eclipse.birt.report.model.api.DesignFileException;
import org.eclipse.birt.report.model.api.ErrorDetail;
import org.eclipse.birt.report.model.api.Expression;
import org.eclipse.birt.report.model.api.ExpressionType;
import org.eclipse.birt.report.model.api.IDesignEngine;
import org.eclipse.birt.report.model.api.IDesignEngineFactory;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.ScalarParameterHandle;
import org.eclipse.birt.report.model.api.SessionHandle;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BIRT 4.8 리포트 도구 — IDE 플러그인이 별도 JVM(Java 8, 서버와 같은 버전)으로 실행.
 * 결과는 표준출력에 JSON 한 개, BIRT 로그·진행 메시지는 표준오류.
 *
 * <pre>
 *   validate &lt;rptdesign&gt;                      BIRT 모델 엔진으로 열어서 오류·경고(줄 번호)
 *   params   &lt;rptdesign&gt;                      파라미터 목록
 *   formats                                  이 BIRT 엔진이 만들 수 있는 출력 형식 (pdf, html, doc, docx, ppt, pptx, odt, ods, postscript …)
 *   render   &lt;rptdesign&gt; --format pdf|html|doc|… --out &lt;파일&gt; [--param 이름=값]... [--allow 주소]... [--deny 주소]... [--log FINE]
 *            --layout: DB 데이터셋을 1줄짜리 가짜(문자열 칸은 [컬럼명])로 바꿔 레이아웃만 그림 (DB 접속 없음)
 *            DB 보호: 데이터 소스 주소(odaURL)에 --deny 값이 들어 있거나, --allow를 줬는데 어느 값도 안 들어 있으면 실행하지 않음
 *   design   &lt;rptdesign&gt; --out &lt;html&gt; [--master] [--base 폴더]   설계 화면(이클립스 Layout·Master Page 탭처럼, 엔진·DB 없음)
 * </pre>
 * 종료 코드: 0 정상, 1 리포트 오류·실행 실패, 2 사용법 오류, 3 허용되지 않은 DB라 거부
 */
public final class BirtCli {

    private static final Pattern ODA_URL = Pattern.compile("<property name=\"odaURL\">([^<]*)</property>");

    private BirtCli() {
    }

    public static void main(String[] args) throws Exception {
        // Windows 기본 인코딩(MS949)과 상관없이 UTF-8 — IDE 플러그인이 UTF-8로 읽음
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        // BIRT가 표준출력에 찍는 메시지가 JSON에 섞이지 않게
        System.setOut(System.err);
        Logger.getLogger("org.eclipse.birt").setLevel(Level.SEVERE);
        int code;
        try {
            code = run(args, out);
        } catch (UsageException e) {
            out.println(Json.obj("ok", false, "error", e.getMessage()));
            code = 2;
        } catch (Throwable t) {
            out.println(Json.obj("ok", false, "error", describe(t)));
            if (System.getenv("BIRT_CLI_DEBUG") != null) {
                t.printStackTrace();   // 표준오류 (System.out도 표준오류로 돌려 둠)
            }
            code = 1;
        } finally {
            try {
                Platform.shutdown();
            } catch (Throwable ignored) {
                // 시작 전에 실패한 경우
            }
        }
        System.exit(code);
    }

    /**
     * 설계 화면 서버(OSGi, 이미 시작한 BIRT 플랫폼) 안에서 실행 — 표준출력 대신 결과를 돌려주고 플랫폼은 끄지 않음.
     * design은 플랫폼을 끄므로 서버에서는 안 함 (설계 화면은 서버가 직접 그림)
     * @return {종료 코드, 결과 JSON 한 줄}
     */
    public static String[] serve(String[] args) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        PrintStream out;
        try {
            out = new PrintStream(buffer, true, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        int code;
        try {
            if (args.length > 0 && "design".equals(args[0])) {
                throw new UsageException("설계 화면 서버에서는 design을 하지 않습니다");
            }
            code = run(args, out);
        } catch (UsageException e) {
            out.println(Json.obj("ok", false, "error", e.getMessage()));
            code = 2;
        } catch (Throwable t) {
            out.println(Json.obj("ok", false, "error", describe(t)));
            code = 1;
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        return new String[] {String.valueOf(code), text.substring(text.lastIndexOf('\n') + 1)};
    }

    private static int run(String[] args, PrintStream out) throws Exception {
        if (args.length == 1 && "formats".equals(args[0])) {
            return formats(out);
        }
        if (args.length < 2) {
            throw new UsageException("사용법: validate|params|render|design <rptdesign> [옵션]");
        }
        String cmd = args[0];
        File design = new File(args[1]).getAbsoluteFile();
        if (!design.isFile()) {
            throw new UsageException("파일이 없습니다: " + design);
        }
        if ("validate".equals(cmd)) {
            return validate(design, out);
        } else if ("params".equals(cmd)) {
            return params(design, out);
        } else if ("render".equals(cmd)) {
            return render(design, args, out);
        } else if ("design".equals(cmd)) {
            return design(design, args, out);
        }
        throw new UsageException("알 수 없는 명령: " + cmd);
    }

    // ---------------------------------------------------------------- design

    /**
     * 설계 화면(이클립스 Layout 탭처럼) HTML — 엔진·DB 없이 모델만 읽음.
     * --master: Master Page 탭처럼 용지 전체(머리글·바닥글). --base: 이미지 기준 폴더 (저장 전 내용을 임시 파일로 그릴 때 원래 폴더)
     */
    private static int design(File design, String[] args, PrintStream out) throws Exception {
        File output = null;
        File base = design.getParentFile();
        boolean master = false;
        for (int i = 2; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) {
                output = new File(args[++i]).getAbsoluteFile();
            } else if ("--base".equals(args[i]) && i + 1 < args.length) {
                base = new File(args[++i]).getAbsoluteFile();
            } else if ("--master".equals(args[i])) {
                master = true;
            } else {
                throw new UsageException("알 수 없는 옵션: " + args[i]);
            }
        }
        if (output == null) {
            throw new UsageException("--out <html 파일>이 필요합니다");
        }
        long start = System.currentTimeMillis();
        SessionHandle session = newSession();
        try {
            ReportDesignHandle handle = session.openDesign(design.getPath());
            try {
                String html = DesignHtml.render(handle, base, master);
                File dir = output.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                    throw new UsageException("출력 폴더를 만들 수 없습니다: " + dir);
                }
                Files.write(output.toPath(), html.getBytes(StandardCharsets.UTF_8));
            } finally {
                handle.close();
            }
        } finally {
            session.closeAll(false);
            Platform.shutdown();
        }
        out.println(Json.obj("ok", true, "out", output.getPath(), "ms", System.currentTimeMillis() - start));
        return 0;
    }

    // ---------------------------------------------------------------- validate

    private static int validate(File design, PrintStream out) throws Exception {
        SessionHandle session = newSession();
        List<Object> errors = new ArrayList<>();
        List<Object> warnings = new ArrayList<>();
        String version = null;
        String createdBy = null;
        try {
            ReportDesignHandle handle = session.openDesign(design.getPath());
            try {
                handle.checkReport();
                for (Object o : handle.getErrorList()) {
                    errors.add(detail(o));
                }
                for (Object o : handle.getWarningList()) {
                    warnings.add(detail(o));
                }
                version = handle.getVersion();
                createdBy = handle.getStringProperty("createdBy");
            } finally {
                handle.close();
            }
        } catch (DesignFileException e) {
            for (ErrorDetail d : e.getErrorList()) {
                errors.add(detail(d));
            }
            if (errors.isEmpty()) {
                errors.add(Json.obj("line", 0, "message", describe(e)));
            }
        } finally {
            session.closeAll(false);
        }
        out.println(Json.obj("ok", errors.isEmpty(), "version", version, "createdBy", createdBy,
                "errors", errors, "warnings", warnings));
        return errors.isEmpty() ? 0 : 1;
    }

    private static Map<String, Object> detail(Object o) {
        if (o instanceof ErrorDetail) {
            ErrorDetail d = (ErrorDetail) o;
            String element = d.getElement() == null ? null : d.getElement().getIdentifier();
            return Json.obj("line", d.getLineNo(), "code", d.getErrorCode(), "element", element,
                    "message", d.getMessage());
        }
        return Json.obj("line", 0, "message", String.valueOf(o));
    }

    // ---------------------------------------------------------------- params

    private static int params(File design, PrintStream out) throws Exception {
        SessionHandle session = newSession();
        List<Object> list = new ArrayList<>();
        try {
            ReportDesignHandle handle = session.openDesign(design.getPath());
            try {
                for (Object o : handle.getAllParameters()) {
                    if (!(o instanceof ScalarParameterHandle)) {
                        continue;
                    }
                    ScalarParameterHandle p = (ScalarParameterHandle) o;
                    List<?> defaults = p.getDefaultValueList();
                    String def = defaults == null || defaults.isEmpty() ? null : stripQuotes(String.valueOf(defaults.get(0)));
                    list.add(Json.obj("name", p.getName(), "dataType", p.getDataType(), "required", p.isRequired(),
                            "hidden", p.isHidden(), "defaultValue", def, "prompt", p.getPromptText()));
                }
            } finally {
                handle.close();
            }
        } finally {
            session.closeAll(false);
        }
        out.println(Json.obj("ok", true, "params", list));
        return 0;
    }

    /** 기본값 식이 "abc" 같은 문자열 상수면 따옴표를 뗌 */
    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---------------------------------------------------------------- render

    private static int render(File design, String[] args, PrintStream out) throws Exception {
        String format = "pdf";
        File output = null;
        Map<String, String> values = new LinkedHashMap<>();
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        Level logLevel = Level.SEVERE;
        boolean layout = false;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : null;
            if ("--format".equals(a) && v != null) {
                format = v;
                i++;
            } else if ("--out".equals(a) && v != null) {
                output = new File(v).getAbsoluteFile();
                i++;
            } else if ("--param".equals(a) && v != null && v.indexOf('=') > 0) {
                values.put(v.substring(0, v.indexOf('=')), v.substring(v.indexOf('=') + 1));
                i++;
            } else if ("--allow".equals(a) && v != null) {
                allow.add(v);
                i++;
            } else if ("--deny".equals(a) && v != null) {
                deny.add(v);
                i++;
            } else if ("--log".equals(a) && v != null) {
                logLevel = Level.parse(v);
                i++;
            } else if ("--layout".equals(a)) {
                layout = true;
            } else {
                throw new UsageException("알 수 없는 옵션: " + a);
            }
        }
        if (!format.matches("[a-z]+")) {
            throw new UsageException("--format은 pdf·html·docx 같은 형식 이름 (formats로 목록)");
        }
        if (output == null) {
            throw new UsageException("--out이 필요합니다");
        }
        // DB 보호: 리포트 파일에 적힌 접속 주소를 그대로 보고 판단 (BIRT로 열기 전 — 접속 자체를 안 함)
        //   --layout은 데이터셋을 떼고 그려서 DB에 접속하지 않음 → 검사 안 함
        String blocked = layout ? null
                : blockedReason(new String(Files.readAllBytes(design.toPath()), StandardCharsets.UTF_8), allow, deny);
        if (blocked != null) {
            out.println(Json.obj("ok", false, "blocked", true, "error", blocked));
            return 3;
        }

        long start = System.currentTimeMillis();
        EngineConfig config = new EngineConfig();
        config.setLogConfig(null, logLevel);   // --log FINE 등: BIRT 로그를 표준오류로 (조회 실행 여부 확인용)
        Logger.getLogger("org.eclipse.birt").setLevel(logLevel);
        // 이미지 등 리소스는 리포트가 있는 폴더(WebContent) 기준 — 서버 birt 앱 루트와 같음
        config.setResourcePath(design.getParentFile().getPath());
        Platform.startup(config);
        IReportEngineFactory factory = (IReportEngineFactory) Platform
                .createFactoryObject(IReportEngineFactory.EXTENSION_REPORT_ENGINE_FACTORY);
        IReportEngine engine = factory.createReportEngine(config);
        List<Object> errors = new ArrayList<>();
        try {
            IReportRunnable runnable;
            if (layout) {
                // 레이아웃만: DB 데이터셋을 같은 이름·컬럼의 스크립트 데이터셋(1줄, 문자열 칸은 [컬럼명])으로 바꿔서 그림 — DB 접속 없음
                byte[] xml = LayoutDesign.toLayoutOnly(Files.readAllBytes(design.toPath()));
                runnable = engine.openReportDesign(design.getPath(), new java.io.ByteArrayInputStream(xml));
            } else {
                runnable = engine.openReportDesign(design.getPath());
            }
            ReportDesignHandle handle = (ReportDesignHandle) runnable.getDesignHandle();
            Map<String, String> types = new LinkedHashMap<>();
            for (Object o : handle.getAllParameters()) {
                if (o instanceof ScalarParameterHandle) {
                    ScalarParameterHandle p = (ScalarParameterHandle) o;
                    types.put(p.getName(), p.getDataType());
                    // 레이아웃만: 고정 기본값이 없는 필수 파라미터는 자리 표시 값 (안 넣으면 실행 전에 거부됨)
                    //   기본값이 스크립트(예: null)인 것도 비어서 거부되므로 자리 표시 값으로 — 데이터는 가짜라 값은 상관없음
                    if (layout && p.isRequired() && !values.containsKey(p.getName()) && !hasConstantDefault(p)) {
                        values.put(p.getName(), placeholder(p.getDataType()));
                    }
                }
            }
            IRunAndRenderTask task = engine.createRunAndRenderTask(runnable);
            try {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    if (!types.containsKey(e.getKey())) {
                        throw new UsageException("리포트에 없는 파라미터: " + e.getKey());
                    }
                    task.setParameterValue(e.getKey(), convert(e.getValue(), types.get(e.getKey())));
                }
                File dir = output.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                    throw new UsageException("출력 폴더를 만들 수 없습니다: " + dir);
                }
                task.setRenderOption(renderOption(format, output));
                task.run();
                for (Object e : task.getErrors()) {
                    errors.add(e instanceof Throwable ? describe((Throwable) e) : String.valueOf(e));
                }
            } finally {
                task.close();
            }
        } finally {
            engine.destroy();
        }
        // 결과 파일이 만들어졌으면 성공 — 리포트 스크립트 오류 등(errors)은 서버에서도 같이 나는 경고라 함께 돌려줌
        boolean ok = output.isFile();
        out.println(Json.obj("ok", ok, "out", output.getPath(), "format", format,
                "ms", System.currentTimeMillis() - start, "errors", errors));
        return ok ? 0 : 1;
    }

    // ---------------------------------------------------------------- formats

    /** 출력 형식 목록 — 설치된 emitter 번들에 따라 다름 (경량판에는 docx가 없음) */
    private static int formats(PrintStream out) throws Exception {
        EngineConfig config = new EngineConfig();
        Platform.startup(config);
        IReportEngineFactory factory = (IReportEngineFactory) Platform
                .createFactoryObject(IReportEngineFactory.EXTENSION_REPORT_ENGINE_FACTORY);
        IReportEngine engine = factory.createReportEngine(config);
        try {
            List<Object> formats = new ArrayList<>(java.util.Arrays.asList(engine.getSupportedFormats()));
            out.println(Json.obj("ok", true, "formats", formats));
            return 0;
        } finally {
            engine.destroy();
        }
    }

    /** 데이터 소스 주소 중 차단 목록에 걸리거나, 허용 목록이 있는데 어디에도 안 맞는 것이 있으면 그 이유 */
    static String blockedReason(String designXml, List<String> allow, List<String> deny) {
        Matcher m = ODA_URL.matcher(designXml);
        while (m.find()) {
            String url = m.group(1);
            String host = url.lastIndexOf('@') >= 0 ? url.substring(url.lastIndexOf('@') + 1) : url;
            for (String d : deny) {
                if (url.contains(d)) {
                    return "차단 목록의 DB(" + d + ")에 접속하는 리포트라 미리보기를 하지 않습니다";
                }
            }
            if (!allow.isEmpty()) {
                boolean ok = false;
                for (String a : allow) {
                    ok |= url.contains(a);
                }
                if (!ok) {
                    return "허용 목록에 없는 DB(" + host + ")에 접속하는 리포트라 미리보기를 하지 않습니다";
                }
            }
        }
        return null;
    }

    private static IRenderOption renderOption(String format, File output) {
        if ("pdf".equals(format)) {
            PDFRenderOption pdf = new PDFRenderOption();
            pdf.setOutputFileName(output.getPath());
            pdf.setOutputFormat("pdf");
            return pdf;
        }
        if (!"html".equals(format)) {
            // doc·docx·ppt·pptx·odt·ods·postscript 등 — 형식별 기본 설정 (이클립스 Run > View Report와 같음)
            RenderOption other = new RenderOption();
            other.setOutputFileName(output.getPath());
            other.setOutputFormat(format);
            return other;
        }
        HTMLRenderOption html = new HTMLRenderOption();
        html.setOutputFileName(output.getPath());
        html.setOutputFormat("html");
        html.setHtmlPagination(false);
        // 이미지는 HTML 옆 폴더에 파일로 (IDE 미리보기 창에서 상대 경로로 보임)
        File images = new File(output.getParentFile(), output.getName() + "_images");
        html.setImageDirectory(images.getPath());
        html.setBaseImageURL(images.getName());
        html.setImageHandler(new HTMLCompleteImageHandler());
        return html;
    }

    private static boolean hasConstantDefault(ScalarParameterHandle p) {
        List<?> defaults = p.getDefaultValueList();
        if (defaults != null) {
            for (Object d : defaults) {
                if (d instanceof Expression && ExpressionType.CONSTANT.equals(((Expression) d).getType())
                        && ((Expression) d).getStringExpression() != null && !((Expression) d).getStringExpression().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 레이아웃만 그릴 때 필수 파라미터에 넣는 값 — convert()로 바뀌는 형식 */
    private static String placeholder(String dataType) {
        if ("integer".equals(dataType) || "decimal".equals(dataType) || "float".equals(dataType)) {
            return "0";
        }
        if ("date".equals(dataType)) {
            return java.time.LocalDate.now().toString();
        }
        if ("boolean".equals(dataType)) {
            return "false";
        }
        return "-";
    }

    private static Object convert(String value, String dataType) {
        if (value == null || dataType == null) {
            return value;
        }
        switch (dataType) {
            case "integer":
                return Integer.valueOf(value.trim());
            case "float":
                return Double.valueOf(value.trim());
            case "decimal":
                return new java.math.BigDecimal(value.trim());
            case "boolean":
                return Boolean.valueOf(value.trim());
            case "date":
                return java.sql.Date.valueOf(value.trim());
            default:
                return value;
        }
    }

    // ---------------------------------------------------------------- 공통

    private static SessionHandle newSession() throws Exception {
        Platform.startup(new DesignConfig());
        return openSession();
    }

    /** 이미 시작한 플랫폼에서 디자인(모델) 세션 */
    private static SessionHandle openSession() {
        IDesignEngineFactory factory = (IDesignEngineFactory) Platform
                .createFactoryObject(IDesignEngineFactory.EXTENSION_DESIGN_ENGINE_FACTORY);
        IDesignEngine engine = factory.createDesignEngine(new DesignConfig());
        return engine.newSessionHandle(ULocale.KOREA);
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 2000; c = c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" ← ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }
}
