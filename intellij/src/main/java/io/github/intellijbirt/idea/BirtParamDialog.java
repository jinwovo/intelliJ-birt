package io.github.intellijbirt.idea;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/** 미리보기 파라미터 입력 — 지난번 입력값(리포트별)을 기억, 없으면 리포트 기본값 */
final class BirtParamDialog extends DialogWrapper {

    private final Project project;
    private final String reportPath;
    private final Map<String, JBTextField> fields = new LinkedHashMap<>();
    private final Map<String, JsonObject> defs = new LinkedHashMap<>();

    BirtParamDialog(Project project, String reportPath, String reportName, JsonArray params, String format) {
        super(project);
        this.project = project;
        this.reportPath = reportPath;
        for (JsonElement e : params) {
            JsonObject p = e.getAsJsonObject();
            defs.put(p.get("name").getAsString(), p);
        }
        setTitle("BIRT 미리보기 (" + format.toUpperCase() + ") — " + reportName);
        setOKButtonText("미리보기");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        FormBuilder form = FormBuilder.createFormBuilder();
        if (defs.isEmpty()) {
            form.addComponent(new JBLabel("파라미터 없음"));
        }
        PropertiesComponent store = PropertiesComponent.getInstance(project);
        for (Map.Entry<String, JsonObject> e : defs.entrySet()) {
            JsonObject p = e.getValue();
            String last = store.getValue(key(e.getKey()));
            String def = BirtValidateAnnotator.str(p, "defaultValue");
            JBTextField field = new JBTextField(last != null ? last : StringUtil.notNullize(def));
            fields.put(e.getKey(), field);
            boolean required = p.has("required") && p.get("required").getAsBoolean();
            String label = e.getKey() + " (" + StringUtil.notNullize(BirtValidateAnnotator.str(p, "dataType"), "string")
                    + (required ? ", 필수" : "") + "):";
            form.addLabeledComponent(label, field);
            String prompt = BirtValidateAnnotator.str(p, "prompt");
            if (prompt != null && !prompt.equals(e.getKey())) {
                form.addComponentToRightColumn(new JBLabel(prompt));
            }
        }
        form.addComponent(new JBLabel("허용·차단 DB 목록(설정, " + BirtCliRunner.PROJECT_CONFIG + ")에 따라 실행하지 않을 수 있습니다. 결과가 비어 있으면 조회 결과가 0건인 것입니다."));
        return form.getPanel();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        for (Map.Entry<String, JBTextField> e : fields.entrySet()) {
            JsonObject p = defs.get(e.getKey());
            String v = e.getValue().getText().trim();
            boolean required = p.has("required") && p.get("required").getAsBoolean();
            if (required && v.isEmpty()) {
                return new ValidationInfo("필수 파라미터입니다", e.getValue());
            }
            if ("integer".equals(BirtValidateAnnotator.str(p, "dataType")) && !v.isEmpty() && !v.matches("-?\\d+")) {
                return new ValidationInfo("정수를 입력하세요", e.getValue());
            }
        }
        return null;
    }

    /** 입력값 (빈 값은 넘기지 않음 — 리포트 기본값 사용), 다음 실행을 위해 기억 */
    @NotNull Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        PropertiesComponent store = PropertiesComponent.getInstance(project);
        for (Map.Entry<String, JBTextField> e : fields.entrySet()) {
            String v = e.getValue().getText().trim();
            store.setValue(key(e.getKey()), v);
            if (!v.isEmpty()) {
                values.put(e.getKey(), v);
            }
        }
        return values;
    }

    private String key(String param) {
        return "birt.preview.param." + reportPath + "#" + param;
    }
}
