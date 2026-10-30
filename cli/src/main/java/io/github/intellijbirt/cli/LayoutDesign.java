package io.github.intellijbirt.cli;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 레이아웃만 보기용 리포트 변환 — DB에 접속하지 않고 모양을 그리려고 데이터 소스·데이터셋을 스크립트로 바꿈.
 * <ul>
 *   <li>oda-data-source → 같은 이름의 script-data-source</li>
 *   <li>oda-data-set → 같은 이름·같은 컬럼의 script-data-set, 1줄만 돌려줌 (문자열 컬럼은 "[컬럼명]", 나머지는 빈값)
 *       — 데이터셋에 묶인 표·목록도 한 번씩 그려짐. 계산 컬럼은 유지, 파라미터·필터·SQL·데이터셋 스크립트는 뺌</li>
 * </ul>
 * 표·라벨 등은 이름으로 데이터셋을 가리키므로 그대로 연결됨. 원본 파일은 바꾸지 않음 (메모리에서만).
 */
final class LayoutDesign {

    private LayoutDesign() {
    }

    static byte[] toLayoutOnly(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        for (Element src : elements(doc, "oda-data-source")) {
            Element script = doc.createElementNS(src.getNamespaceURI(), "script-data-source");
            copyAttr(src, script, "name");
            copyAttr(src, script, "id");
            src.getParentNode().replaceChild(script, src);
        }
        for (Element ds : elements(doc, "oda-data-set")) {
            ds.getParentNode().replaceChild(scriptDataSet(doc, ds), ds);
        }

        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream(xml.length);
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private static Element scriptDataSet(Document doc, Element ds) {
        String ns = ds.getNamespaceURI();
        Element script = doc.createElementNS(ns, "script-data-set");
        copyAttr(ds, script, "name");
        copyAttr(ds, script, "id");

        // 컬럼: 데이터셋 바로 아래 resultSet, 없으면 cachedMetaData 안의 resultSet
        Element resultSet = child(ds, "list-property", "resultSet");
        if (resultSet == null) {
            Element cached = child(ds, "structure", "cachedMetaData");
            resultSet = cached == null ? null : child(cached, "list-property", "resultSet");
        }
        // 스크립트 데이터셋의 컬럼 정의 = resultSetHints + cachedMetaData/resultSet (top-level resultSet은 ODA 전용)
        StringBuilder fetch = new StringBuilder("if (__layoutRow++ >= 1) return false;\n");
        if (resultSet != null) {
            Element hints = doc.createElementNS(ns, "list-property");
            hints.setAttribute("name", "resultSetHints");
            Element cached = doc.createElementNS(ns, "structure");
            cached.setAttribute("name", "cachedMetaData");
            Element cachedColumns = doc.createElementNS(ns, "list-property");
            cachedColumns.setAttribute("name", "resultSet");
            cached.appendChild(cachedColumns);
            for (Element col : childElements(resultSet, "structure")) {
                String name = propText(col, "name");
                String type = propText(col, "dataType");
                if (name == null) {
                    continue;
                }
                hints.appendChild(column(doc, ns, propText(col, "position"), name, type));
                cachedColumns.appendChild(column(doc, ns, propText(col, "position"), name, type));
                if (type == null || "string".equals(type)) {
                    String js = name.replace("\\", "\\\\").replace("\"", "\\\"");
                    fetch.append("row[\"").append(js).append("\"] = \"[").append(js).append("]\";\n");
                }
            }
            script.appendChild(hints);
            script.appendChild(cached);
        }
        fetch.append("return true;");

        // 계산 컬럼은 표의 바인딩이 참조할 수 있어서 유지
        //   데이터셋 파라미터는 뺌 — ODA 파라미터 구조(nativeName 등)가 스크립트 데이터셋에 없어서 모델이 못 읽음, 1줄 고정이라 필요 없음
        Element computed = child(ds, "list-property", "computedColumns");
        if (computed != null) {
            script.appendChild(computed.cloneNode(true));
        }
        Element dataSource = child(ds, "property", "dataSource");
        if (dataSource != null) {
            script.appendChild(dataSource.cloneNode(true));
        }
        script.appendChild(method(doc, ns, "open", "__layoutRow = 0;"));
        script.appendChild(method(doc, ns, "fetch", fetch.toString()));
        return script;
    }

    // ---------------------------------------------------------------- DOM 도우미

    private static List<Element> elements(Document doc, String localName) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            list.add((Element) nodes.item(i));
        }
        return list;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                list.add((Element) n);
            }
        }
        return list;
    }

    /** &lt;태그 name="이름"&gt; 바로 아래 자식 */
    private static Element child(Element parent, String localName, String nameAttr) {
        for (Element e : childElements(parent, localName)) {
            if (nameAttr.equals(e.getAttribute("name"))) {
                return e;
            }
        }
        return null;
    }

    private static String propText(Element structure, String name) {
        Element p = child(structure, "property", name);
        return p == null ? null : p.getTextContent().trim();
    }

    private static void addProp(Document doc, Element parent, String name, String value) {
        if (value == null) {
            return;
        }
        Element p = doc.createElementNS(parent.getNamespaceURI(), "property");
        p.setAttribute("name", name);
        p.setTextContent(value);
        parent.appendChild(p);
    }

    private static Element column(Document doc, String ns, String position, String name, String type) {
        Element s = doc.createElementNS(ns, "structure");
        addProp(doc, s, "position", position);
        addProp(doc, s, "name", name);
        addProp(doc, s, "dataType", type == null ? "string" : type);
        return s;
    }

    private static Element method(Document doc, String ns, String name, String body) {
        Element m = doc.createElementNS(ns, "method");
        m.setAttribute("name", name);
        m.appendChild(doc.createCDATASection(body));
        return m;
    }

    private static void copyAttr(Element from, Element to, String attr) {
        if (from.hasAttribute(attr)) {
            to.setAttribute(attr, from.getAttribute(attr));
        }
    }
}
