package io.github.intellijbirt.idea;

import com.intellij.icons.AllIcons;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.structureView.StructureViewBuilderProvider;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BIRT 리포트 구조 보기: 데이터 소스 / 데이터셋(SQL·스크립트) / 파라미터 / 본문(표·라벨…) / 마스터 페이지 / 스타일.
 * 노드를 누르면 해당 XML 위치로 이동.
 */
public final class BirtStructureViewProvider implements StructureViewBuilderProvider {

    @Override
    public @Nullable StructureViewBuilder getStructureViewBuilder(@NotNull FileType fileType, @NotNull VirtualFile vf,
                                                                 @NotNull Project project) {
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        return psi instanceof XmlFile ? createStructureViewBuilder((XmlFile) psi) : null;
    }

    private static @Nullable StructureViewBuilder createStructureViewBuilder(@NotNull XmlFile file) {
        if (!BirtFileType.isBirtFile(file.getVirtualFile())) {
            return null;
        }
        XmlTag root = file.getRootTag();
        if (root == null) {
            return null;
        }
        return new TreeBasedStructureViewBuilder() {
            @Override
            public @NotNull StructureViewModel createStructureViewModel(@Nullable Editor editor) {
                return new StructureViewModelBase(file, editor, new Node(root));
            }

            @Override
            public boolean isRootNodeShown() {
                return false;
            }
        };
    }

    /** 본문에서 보여줄 요소 (column은 너무 많아서 뺌) */
    private static final Set<String> ITEMS = Set.of("grid", "table", "list", "label", "text", "data", "image",
            "extended-item", "text-data", "auto-text", "line", "rectangle", "row", "cell", "header", "detail",
            "footer", "group");

    /** 최상위 묶음: 태그 → 표시 이름 */
    private static final Map<String, String> SECTIONS = Map.of(
            "data-sources", "데이터 소스", "data-sets", "데이터셋", "parameters", "파라미터",
            "body", "본문", "page-setup", "마스터 페이지", "styles", "스타일");

    /** 구조 보기 노드 = XML 태그 하나 (누르면 그 태그로 이동) */
    static final class Node implements StructureViewTreeElement, ItemPresentation {

        private final XmlTag tag;

        Node(XmlTag tag) {
            this.tag = tag;
        }

        private @Nullable XmlTag getElement() {
            return tag.isValid() ? tag : null;
        }

        @Override
        public Object getValue() {
            return tag;
        }

        @Override
        public @NotNull ItemPresentation getPresentation() {
            return this;
        }

        @Override
        public void navigate(boolean requestFocus) {
            if (tag.isValid() && tag instanceof Navigatable n) {
                n.navigate(requestFocus);
            }
        }

        @Override
        public boolean canNavigate() {
            return tag.isValid() && tag instanceof Navigatable n && n.canNavigate();
        }

        @Override
        public boolean canNavigateToSource() {
            return canNavigate();
        }

        @Override
        public TreeElement @NotNull [] getChildren() {
            return getChildrenBase().toArray(TreeElement.EMPTY_ARRAY);
        }

        private @NotNull Collection<StructureViewTreeElement> getChildrenBase() {
            XmlTag tag = getElement();
            List<StructureViewTreeElement> children = new ArrayList<>();
            if (tag == null) {
                return children;
            }
            String name = tag.getLocalName();
            for (XmlTag child : tag.getSubTags()) {
                String c = child.getLocalName();
                boolean show;
                switch (name) {
                    case "report":
                    case "library":
                        show = SECTIONS.containsKey(c) || "method".equals(c);
                        break;
                    case "data-sources":
                    case "data-sets":
                    case "parameters":
                    case "parameter-group":
                    case "styles":
                    case "page-setup":
                        show = true;
                        break;
                    case "oda-data-set":
                    case "script-data-set":
                        show = "method".equals(c) || isQueryText(child);
                        break;
                    default:
                        show = ITEMS.contains(c);
                }
                if (show) {
                    children.add(new Node(child));
                }
            }
            return children;
        }

        @Override
        public @Nullable String getPresentableText() {
            XmlTag tag = getElement();
            if (tag == null) {
                return null;
            }
            String t = tag.getLocalName();
            if (SECTIONS.containsKey(t)) {
                return SECTIONS.get(t) + " (" + tag.getSubTags().length + ")";
            }
            String name = tag.getAttributeValue("name");
            switch (t) {
                case "oda-data-source":
                    return name + "  " + StringUtil.notNullize(hostOf(prop(tag, "odaURL")));
                case "oda-data-set":
                case "script-data-set":
                    return name + "  ← " + StringUtil.notNullize(prop(tag, "dataSource"), "?");
                case "scalar-parameter":
                    return name + "  (" + StringUtil.notNullize(prop(tag, "dataType"), "string")
                            + ("true".equals(prop(tag, "isRequired")) ? ", 필수" : "") + ")";
                case "parameter-group":
                    return "그룹 " + name;
                case "method":
                    return "스크립트 " + name;
                case "xml-property":
                    return "SQL  " + snippet(tag.getValue().getTrimmedText());
                case "label":
                    return "라벨 \"" + snippet(textProp(tag, "text")) + "\"";
                case "text":
                    return "텍스트 \"" + snippet(textProp(tag, "content")) + "\"";
                case "data":
                    return "데이터 [" + StringUtil.notNullize(prop(tag, "resultSetColumn"), "?") + "]";
                case "table":
                case "list":
                    return t + (name == null ? "" : " " + name) + "  ← " + StringUtil.notNullize(prop(tag, "dataSet"), "-");
                case "image":
                    return "이미지 " + StringUtil.notNullize(prop(tag, "uri"), StringUtil.notNullize(prop(tag, "resource"), ""));
                case "cell":
                    return "셀";
                case "row":
                    return "행";
                case "simple-master-page":
                case "style":
                    return name;
                default:
                    return t + (name == null ? "" : " " + name);
            }
        }

        @Override
        public @Nullable String getLocationString() {
            XmlTag tag = getElement();
            String id = tag == null ? null : tag.getAttributeValue("id");
            return id == null ? null : "#" + id;
        }

        @Override
        public Icon getIcon(boolean open) {
            XmlTag tag = getElement();
            String t = tag == null ? "" : tag.getLocalName();
            switch (t) {
                case "data-sources":
                case "data-sets":
                case "parameters":
                case "body":
                case "page-setup":
                case "styles":
                case "parameter-group":
                    return AllIcons.Nodes.Folder;
                case "oda-data-source":
                case "oda-data-set":
                case "script-data-set":
                case "table":
                case "grid":
                case "list":
                    return AllIcons.Nodes.DataTables;
                case "scalar-parameter":
                    return AllIcons.Nodes.Parameter;
                case "method":
                    return AllIcons.Nodes.Function;
                case "xml-property":
                    return AllIcons.FileTypes.Text;
                default:
                    return AllIcons.Nodes.Tag;
            }
        }
    }

    static boolean isQueryText(XmlTag tag) {
        return "xml-property".equals(tag.getLocalName()) && "queryText".equals(tag.getAttributeValue("name"));
    }

    /** &lt;property name="이름"&gt;값&lt;/property&gt; */
    static @Nullable String prop(XmlTag tag, String name) {
        for (XmlTag p : tag.findSubTags("property")) {
            if (name.equals(p.getAttributeValue("name"))) {
                return p.getValue().getTrimmedText();
            }
        }
        return null;
    }

    /** &lt;text-property name="이름"&gt; 또는 &lt;property name="이름"&gt; */
    static @Nullable String textProp(XmlTag tag, String name) {
        for (XmlTag p : tag.findSubTags("text-property")) {
            if (name.equals(p.getAttributeValue("name"))) {
                return p.getValue().getTrimmedText();
            }
        }
        return prop(tag, name);
    }

    /** jdbc:tibero:thin:@dbhost:8629:DBNAME → dbhost:8629:DBNAME (계정 정보 없음) */
    static @Nullable String hostOf(@Nullable String url) {
        if (url == null) {
            return null;
        }
        int at = url.lastIndexOf('@');
        return at >= 0 ? url.substring(at + 1) : url;
    }

    static String snippet(@Nullable String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 40 ? one.substring(0, 40) + "…" : one;
    }
}
