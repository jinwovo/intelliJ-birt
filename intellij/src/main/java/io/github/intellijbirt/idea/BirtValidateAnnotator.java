package io.github.intellijbirt.idea;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 저장된 rptdesign을 BIRT 4.8 모델 엔진(birt-cli validate)으로 검사해서 오류·경고 줄에 표시.
 * 저장하지 않은 변경이 있으면 건너뜀 (디스크 파일을 검사) — 저장할 때마다 한 번, 같은 내용이면 결과 재사용.
 * 서버 BIRT 4.8과 파일 형식(3.2.23)·작성 디자이너 버전이 다르면 파일 맨 위에 경고.
 */
public final class BirtValidateAnnotator extends ExternalAnnotator<VirtualFile, JsonObject> {

    static final String EXPECTED_VERSION = "3.2.23";
    static final String EXPECTED_DESIGNER = "4.8.0";

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(long stamp, JsonObject result) {
    }

    @Override
    public @Nullable VirtualFile collectInformation(@NotNull PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        if (!BirtFileType.isBirtFile(vf) || !vf.isInLocalFileSystem()) {
            return null;
        }
        Document doc = FileDocumentManager.getInstance().getCachedDocument(vf);
        if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            return null;
        }
        return vf;
    }

    @Override
    public @Nullable JsonObject doAnnotate(VirtualFile vf) {
        if (vf == null) {
            return null;
        }
        long stamp = vf.getTimeStamp();
        Cached cached = CACHE.get(vf.getPath());
        if (cached != null && cached.stamp() == stamp) {
            return cached.result();
        }
        try {
            BirtCliRunner.Result r = BirtCliRunner.run(vf, List.of("validate", vf.getPath()), 60);
            if (r.json == null) {
                return null;
            }
            CACHE.put(vf.getPath(), new Cached(stamp, r.json));
            return r.json;
        } catch (BirtCliRunner.SetupException e) {
            JsonObject setup = new JsonObject();
            setup.addProperty("setupError", e.getMessage());
            return setup;
        }
    }

    @Override
    public void apply(@NotNull PsiFile file, JsonObject result, @NotNull AnnotationHolder holder) {
        if (result == null) {
            return;
        }
        if (result.has("setupError")) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "BIRT 검사 안 함: " + result.get("setupError").getAsString())
                    .fileLevel().create();
            return;
        }
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (doc == null) {
            return;
        }
        annotate(result, "errors", HighlightSeverity.ERROR, doc, holder);
        annotate(result, "warnings", HighlightSeverity.WARNING, doc, holder);

        String version = str(result, "version");
        String createdBy = str(result, "createdBy");
        if (version != null && !EXPECTED_VERSION.equals(version)) {
            holder.newAnnotation(HighlightSeverity.WARNING,
                    "BIRT 파일 형식 " + version + " — 서버 BIRT 4.8은 " + EXPECTED_VERSION
                            + ". 다른 버전 디자이너로 저장했으면 서버에서 안 열리거나 다르게 나올 수 있음").fileLevel().create();
        } else if (createdBy != null && !createdBy.contains(EXPECTED_DESIGNER)) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING,
                    "작성 디자이너: " + createdBy + " (서버는 BIRT " + EXPECTED_DESIGNER + ")").fileLevel().create();
        }
    }

    private static void annotate(JsonObject result, String key, HighlightSeverity severity, Document doc,
                                 AnnotationHolder holder) {
        JsonArray items = result.has(key) && result.get(key).isJsonArray() ? result.getAsJsonArray(key) : null;
        if (items == null) {
            return;
        }
        for (JsonElement e : items) {
            JsonObject item = e.getAsJsonObject();
            int line = item.has("line") ? item.get("line").getAsInt() : 0;
            String element = str(item, "element");
            String message = "BIRT: " + str(item, "message") + (element == null ? "" : " [" + element + "]");
            if (line >= 1 && line <= doc.getLineCount()) {
                TextRange range = new TextRange(doc.getLineStartOffset(line - 1), doc.getLineEndOffset(line - 1));
                holder.newAnnotation(severity, message).range(range).create();
            } else {
                holder.newAnnotation(severity, message).fileLevel().create();
            }
        }
    }

    static @Nullable String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** 검사 결과 재사용 안 함 (설정을 바꾼 뒤 등) */
    static void clearCache() {
        CACHE.clear();
    }
}
