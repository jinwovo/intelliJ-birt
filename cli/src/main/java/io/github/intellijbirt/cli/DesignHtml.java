package io.github.intellijbirt.cli;

import org.eclipse.birt.report.model.api.CellHandle;
import org.eclipse.birt.report.model.api.ColumnHandle;
import org.eclipse.birt.report.model.api.ComputedColumnHandle;
import org.eclipse.birt.report.model.api.DataItemHandle;
import org.eclipse.birt.report.model.api.DesignElementHandle;
import org.eclipse.birt.report.model.api.GridHandle;
import org.eclipse.birt.report.model.api.ImageHandle;
import org.eclipse.birt.report.model.api.LabelHandle;
import org.eclipse.birt.report.model.api.PropertyHandle;
import org.eclipse.birt.report.model.api.ReportDesignHandle;
import org.eclipse.birt.report.model.api.ReportItemHandle;
import org.eclipse.birt.report.model.api.RowHandle;
import org.eclipse.birt.report.model.api.SimpleMasterPageHandle;
import org.eclipse.birt.report.model.api.SlotHandle;
import org.eclipse.birt.report.model.api.TableGroupHandle;
import org.eclipse.birt.report.model.api.TableHandle;
import org.eclipse.birt.report.model.api.TextDataHandle;
import org.eclipse.birt.report.model.api.TextItemHandle;
import org.eclipse.birt.report.model.api.metadata.DimensionValue;

import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설계 화면(이클립스 BIRT 디자이너의 Layout 탭)처럼 리포트 설계를 HTML로 그림 — 엔진을 돌리지 않고 모델만 읽음.
 * <ul>
 *   <li>데이터 칸은 [바인딩 이름], 라벨·텍스트는 그대로, 숨김 조건과 상관없이 모든 요소를 그림</li>
 *   <li>표는 머리글 → 그룹 머리글 → 상세 → 그룹 바닥글 → 바닥글 행을 한 번씩</li>
 *   <li>스타일 값은 모델이 계산한 값(요소 → 스타일 → 선택자 → 상위 요소 상속)</li>
 *   <li>요소마다 data-id = XML의 id — 화면에서 고른 요소를 XML에서 찾는 데 씀</li>
 * </ul>
 */
final class DesignHtml {

    private static final String[] TEXT_PROPS = {
            "fontFamily", "font-family", "fontSize", "font-size", "fontWeight", "font-weight", "fontStyle", "font-style",
            "fontVariant", "font-variant", "color", "color", "textAlign", "text-align", "letterSpacing", "letter-spacing",
            "wordSpacing", "word-spacing", "lineHeight", "line-height", "textIndent", "text-indent",
            "textTransform", "text-transform", "whiteSpace", "white-space",
    };
    private static final String[] BOX_PROPS = {
            "backgroundColor", "background-color", "verticalAlign", "vertical-align",
            "paddingTop", "padding-top", "paddingRight", "padding-right", "paddingBottom", "padding-bottom",
            "paddingLeft", "padding-left",
    };
    private static final String[] MARGIN_PROPS = {
            "marginTop", "margin-top", "marginRight", "margin-right", "marginBottom", "margin-bottom",
            "marginLeft", "margin-left",
    };
    private static final String[] SIDES = {"Top", "Right", "Bottom", "Left"};

    private final ReportDesignHandle design;
    private final File resourceDir;
    private final StringBuilder html = new StringBuilder(64 * 1024);

    private DesignHtml(ReportDesignHandle design, File resourceDir) {
        this.design = design;
        this.resourceDir = resourceDir;
    }

    /**
     * @param resourceDir 이미지 등 상대 경로의 기준 (리포트가 있는 폴더 — 서버 birt 앱 루트와 같음)
     * @param master      true면 Master Page 탭처럼 용지 전체(머리글·바닥글), false면 Layout 탭처럼 본문
     */
    static String render(ReportDesignHandle design, File resourceDir, boolean master) {
        DesignHtml d = new DesignHtml(design, resourceDir);
        d.page(master);
        return d.html.toString();
    }

    // ---------------------------------------------------------------- 용지

    private void page(boolean masterView) {
        SimpleMasterPageHandle master = null;
        for (Object o : design.getMasterPages().getContents()) {
            if (o instanceof SimpleMasterPageHandle) {
                master = (SimpleMasterPageHandle) o;
                break;
            }
        }
        String width = master == null ? "8.5in" : dim(master.getPageWidth());
        String marginTop = master == null ? "1in" : dim(master.getTopMargin());
        String marginLeft = master == null ? "1in" : dim(master.getLeftMargin());
        String marginRight = master == null ? "1in" : dim(master.getRightMargin());
        String marginBottom = master == null ? "1in" : dim(master.getBottomMargin());

        html.append("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>")
                .append(esc(design.getFileName())).append("</title><style>\n")
                .append("html,body{margin:0;padding:0}\n")
                .append("body{background:#a3abc9;font-family:serif;font-size:10pt;padding:28px 0 40px 28px;min-width:min-content}\n")
                .append(".ruler-top{position:sticky;top:0;height:22px;margin:-28px 0 6px -28px;background:#fff;border-bottom:1px solid #999;z-index:5}\n")
                .append(".ruler-left{position:absolute;left:0;top:0;width:22px;background:#fff;border-right:1px solid #999;font:9px sans-serif;color:#333;z-index:4}\n")
                .append(".page{position:relative;background:#fff;margin:0 auto 0 0;box-shadow:2px 2px 0 #7a83a6;display:flex;flex-direction:column}\n")
                // 본문·내용 영역은 용지 아래 끝까지 (이클립스처럼 연한 파란 테두리가 끝까지 이어짐)
                // 안내선은 이클립스처럼 상자 안쪽에, 본문 선이 1px 자리를 차지 (요소는 본문 선 안쪽 4px)
                .append(".body-area,.content{flex:1 0 auto;outline:1px solid #c3d0ea;outline-offset:-1px;padding:1px}\n")
                .append(".content{display:flex;flex-direction:column}\n")
                .append(".band{outline:1px dashed #a9c4e8;flex:none;overflow:hidden}\n")
                .append(".band-body{flex:1}\n")
                .append("table.birt{border-collapse:collapse;table-layout:fixed}\n")
                // 칸 세로 정렬이 지정 안 됐으면 가운데 (이클립스와 같음)
                .append("table.birt>tbody>tr>td{padding:0;box-shadow:inset 0 0 0 1px #d3d9e8;vertical-align:middle;overflow:hidden}\n")
                .append("table.birt{outline:1px solid #c3d0ea;outline-offset:-1px}\n")
                // 지정한 높이·폭에 안쪽 여백 포함 (이클립스처럼 — 0.1in 요소는 여백 포함 0.1in)
                .append(".item{box-sizing:border-box;min-height:1.2em;white-space:pre-wrap;word-break:break-all;box-shadow:inset 0 0 0 1px #d9dde6}\n")
                .append(".item[data-type=Data]{white-space:pre;overflow:hidden}\n")
                // 빈 칸 높이 (이클립스: 높이 안 정한 빈 행 16px = 13px + 칸 여백)
                .append(".empty{height:13px}\n")
                // 칸·본문 안 요소는 위아래 여백이 겹치지 않게 (이클립스는 요소 사이 6px)
                .append(".body-area,.flow,.band{display:flex;flex-direction:column}\n")
                // 자리가 모자라도 요소를 줄이지 않고 넘치는 부분을 자름 (이클립스처럼)
                .append(".body-area>*,.flow>*,.band>*{flex-shrink:0}\n")
                .append(".ph{color:#c8c8c8;font:9pt 'Segoe UI',sans-serif}\n.row-ph{text-align:center;white-space:nowrap;line-height:13px}\n")
                .append(".item[data-type=Image]{box-shadow:none;min-height:0;line-height:0}\n")
                .append(".img-broken{display:inline-block;box-sizing:border-box;width:14px;height:14px;background:#fff;")
                .append("border:1px solid #000;border-left-color:#999;border-top-color:#666;color:#f00;")
                .append("font:bold 11px/12px sans-serif;text-align:center}\n")
                .append(".img-ph{display:inline-block;border:1px dashed #889;color:#667;padding:2px 6px;font:9pt sans-serif}\n")
                .append("[data-id].sel{outline:2px solid #1f6feb;outline-offset:-1px}\n")
                .append("</style>\n")
                // 고칠 때마다 다시 그려도 보던 위치 유지
                .append("<script>try{var p=(sessionStorage.getItem('birt-scroll')||'').split(',');")
                .append("addEventListener('load',function(){if(p.length==2)scrollTo(+p[0],+p[1]);});")
                .append("addEventListener('scroll',function(){sessionStorage.setItem('birt-scroll',scrollX+','+scrollY);});}catch(e){}\n")
                // 왼쪽 눈금자 — 높이는 그려 봐야 알아서 그린 뒤 만듦. 위 여백 끝이 0, 1/8인치(12px)마다 눈금, 1인치마다 숫자
                .append("addEventListener('load',function(){var pg=document.querySelector('.page');if(!pg)return;")
                .append("var top=pg.getBoundingClientRect().top+scrollY,origin=top+parseFloat(getComputedStyle(pg).paddingTop),end=top+pg.offsetHeight;")
                .append("var r=document.createElement('div');r.className='ruler-left';r.style.height=(end+40)+'px';var s='';")
                .append("for(var k=Math.ceil((top-origin)/12);origin+k*12<=end;k++){var y=origin+k*12;")
                .append("s+='<div style=\"position:absolute;top:'+y+'px;right:0;height:1px;width:'+(k%8==0?8:k%4==0?6:3)+'px;background:#555\"></div>';")
                .append("if(k%8==0)s+='<div style=\"position:absolute;top:'+(y+1)+'px;left:2px\">'+Math.abs(k/8)+'</div>';}")
                .append("r.innerHTML=s;document.body.appendChild(r);});</script>\n")
                .append("</head><body>\n");
        ruler(width, marginLeft);
        // 마스터 페이지 배경(로고·직인 등)은 이클립스처럼 Layout에서도 용지에 그림
        StringBuilder pageBackground = new StringBuilder();
        if (master != null) {
            background(pageBackground, master);
        }
        html.append("<div class=\"page\" style=\"width:").append(width).append(";box-sizing:border-box;padding:")
                .append(marginTop).append(' ').append(marginRight).append(' ').append(marginBottom).append(' ')
                .append(marginLeft).append(';').append(esc(pageBackground.toString()));
        if (masterView && master != null) {
            // 용지 전체 높이, 위 머리글 · 가운데 본문 자리 · 아래 바닥글
            // 머리글·바닥글은 점선 영역, 지정한 높이에서 넘치는 내용은 잘림 (이클립스처럼)
            html.append(";min-height:").append(dim(master.getPageHeight())).append("\">\n<div class=\"content\">\n");
            html.append("<div class=\"band\" data-id=\"").append(master.getID()).append("\" style=\"height:")
                    .append(nvl(dim(master.getHeaderHeight()))).append("\">\n");
            children(master.getPageHeader());
            html.append("</div>\n<div class=\"band-body\"></div>\n<div class=\"band\" style=\"height:")
                    .append(nvl(dim(master.getFooterHeight()))).append("\">\n");
            children(master.getPageFooter());
            html.append("</div>\n</div>\n");
        } else {
            // 이클립스처럼 내용이 짧아도 용지 높이만큼 (배경 위치 %도 용지 높이 기준)
            if (master != null) {
                html.append(";min-height:").append(dim(master.getPageHeight()));
            }
            html.append("\">\n<div class=\"body-area\"").append(css(design, false, false)).append(">\n");
            children(design.getBody());
            html.append("</div>\n");
        }
        html.append("</div>\n</body></html>\n");
    }

    /** 위 눈금자 — 이클립스처럼 왼쪽 여백 끝이 0 */
    private void ruler(String pageWidth, String marginLeft) {
        double inches = inches(pageWidth);
        double origin = inches(marginLeft);
        html.append("<div class=\"ruler-top\"><div style=\"position:relative;margin-left:28px;width:").append(pageWidth)
                .append(";height:22px;font:9px sans-serif;color:#333\">");
        // 눈금은 왼쪽 여백 끝(0)부터 1/8인치마다, 숫자는 1인치마다
        for (int k = (int) Math.ceil(-origin * 8); origin + k / 8.0 <= inches; k++) {
            double x = origin + k / 8.0;
            boolean whole = k % 8 == 0;
            boolean half = k % 4 == 0;
            html.append("<div style=\"position:absolute;left:").append(fmt(x)).append("in;bottom:0;width:1px;height:")
                    .append(whole ? 8 : half ? 6 : 3).append("px;background:#555\"></div>");
            if (whole) {
                html.append("<div style=\"position:absolute;left:").append(fmt(x)).append("in;top:1px;transform:translateX(-50%)\">")
                        .append(Math.abs(k / 8)).append("</div>");
            }
        }
        html.append("</div></div>\n");
    }

    // ---------------------------------------------------------------- 요소

    private void children(SlotHandle slot) {
        for (Object o : slot.getContents()) {
            item((DesignElementHandle) o);
        }
    }

    private void item(DesignElementHandle h) {
        if (h instanceof GridHandle) {
            grid((GridHandle) h, ((GridHandle) h).getColumns(), rows(((GridHandle) h).getRows()), null);
        } else if (h instanceof TableHandle) {
            TableHandle t = (TableHandle) h;
            List<RowHandle> rows = new ArrayList<>();
            List<String> types = new ArrayList<>();
            addRows(rows, types, t.getHeader(), "Header Row");
            List<TableGroupHandle> groups = new ArrayList<>();
            for (Object g : t.getGroups().getContents()) {
                groups.add((TableGroupHandle) g);
                addRows(rows, types, ((TableGroupHandle) g).getHeader(), "Group Header Row");
            }
            addRows(rows, types, t.getDetail(), "Detail Row");
            for (int i = groups.size() - 1; i >= 0; i--) {
                addRows(rows, types, groups.get(i).getFooter(), "Group Footer Row");
            }
            addRows(rows, types, t.getFooter(), "Footer Row");
            grid((ReportItemHandle) h, t.getColumns(), rows, types);
        } else if (h instanceof LabelHandle) {
            // 글자가 없으면 이클립스처럼 흐린 "Label"
            String text = ((LabelHandle) h).getDisplayText();
            block(h, text == null || text.isEmpty() ? "<span class=\"ph\">Label</span>" : esc(text));
        } else if (h instanceof DataItemHandle) {
            block(h, esc(bindingLabel((DataItemHandle) h)));
        } else if (h instanceof TextItemHandle) {
            TextItemHandle t = (TextItemHandle) h;
            String content = t.getContent();
            block(h, content == null || content.trim().isEmpty() ? "<span class=\"ph\">Text</span>"
                    : text(content, t.getContentType()));
        } else if (h instanceof TextDataHandle) {
            block(h, esc("[" + nvl(((TextDataHandle) h).getValueExpr()) + "]"));
        } else if (h instanceof ImageHandle) {
            image((ImageHandle) h);
        } else {
            block(h, "<span class=\"img-ph\">" + esc(h.getDefn().getName()) + "</span>");
        }
    }

    /**
     * 데이터 칸 글자 — 이클립스처럼 [바인딩 표시 이름](없으면 바인딩 이름), 집계 바인딩(SUM 등)이면 앞에 ∑.
     * 바인딩은 자기 → 바깥 요소 순으로 찾음
     */
    private static String bindingLabel(DataItemHandle h) {
        String col = h.getResultSetColumn();
        if (col == null) {
            return "[]";
        }
        for (DesignElementHandle e = h; e != null; e = e.getContainer()) {
            PropertyHandle bindings = e.getPropertyHandle("boundDataColumns");
            if (bindings == null) {
                continue;
            }
            for (Iterator<?> it = bindings.iterator(); it.hasNext(); ) {
                Object o = it.next();
                if (o instanceof ComputedColumnHandle && col.equals(((ComputedColumnHandle) o).getName())) {
                    ComputedColumnHandle c = (ComputedColumnHandle) o;
                    String display = c.getDisplayName();
                    String label = "[" + (display == null || display.isEmpty() ? col : display) + "]";
                    return c.getAggregateFunction() == null ? label : "∑" + label;
                }
            }
        }
        return "[" + col + "]";
    }

    private void block(DesignElementHandle h, String content) {
        html.append("<div class=\"item\" data-id=\"").append(h.getID()).append("\" data-type=\"")
                .append(h.getDefn().getName()).append('"').append(css(h, true, true)).append('>')
                .append(content).append("</div>\n");
    }

    /** @param rowTypes 표면 행마다 종류(Detail Row 등), 그리드면 null */
    private void grid(ReportItemHandle h, SlotHandle columnSlot, List<RowHandle> rows, List<String> rowTypes) {
        List<String> widths = new ArrayList<>();
        List<Long> columnIds = new ArrayList<>();
        boolean allFixed = true;
        double total = 0;
        for (Object o : columnSlot.getContents()) {
            ColumnHandle c = (ColumnHandle) o;
            String w = dim(c.getWidth().getValue());
            for (int i = 0; i < Math.max(1, c.getRepeatCount()); i++) {
                widths.add(w);
                columnIds.add(c.getID());
                allFixed &= w != null && !w.endsWith("%");
                total += w == null ? 0 : inches(w);
            }
        }
        // 표 폭이 없으면 열 폭 합 — 브라우저는 표 폭이 auto면 table-layout:fixed를 무시하고 글자 양으로 열을 나눔
        // 비율 폭은 좌우 기본 여백(3px+2px)을 빼야 칸을 넘지 않음
        String width = dim(h.getWidth().getValue());
        StringBuilder style = new StringBuilder();
        if (width != null && width.endsWith("%")) {
            style.append("width:calc(").append(width).append(" - 5px);");
        } else if (width != null) {
            style.append("width:").append(width).append(';');
        } else if (allFixed && !widths.isEmpty()) {
            style.append("width:").append(Math.round(total * 96)).append("px;");
        } else {
            style.append("width:calc(100% - 5px);");
        }
        // 표·그리드 높이 — 행 높이 합보다 크면 이클립스처럼 행이 늘어남
        String tableHeight = dim(h.getHeight().getValue());
        if (tableHeight != null) {
            style.append("height:").append(tableHeight).append(';');
        }
        html.append("<table class=\"birt\" data-id=\"").append(h.getID()).append("\" data-type=\"")
                .append(h.getDefn().getName()).append('"').append(css(h, true, true, style.toString())).append(">\n<colgroup>");
        for (int i = 0; i < widths.size(); i++) {
            html.append("<col data-id=\"").append(columnIds.get(i)).append('"');
            if (widths.get(i) != null) {
                html.append(" style=\"width:").append(widths.get(i)).append('"');
            }
            html.append('>');
        }
        html.append("</colgroup><tbody>\n");
        // 행 높이(인치, 없으면 -1) — 이클립스는 행 높이를 지키고 넘치는 내용은 자름.
        // 표 높이가 행 높이 합보다 크면 행이 비율대로 늘어남 (브라우저 표와 같음)
        double[] rowIn = new double[rows.size()];
        double sum = 0;
        boolean allRows = true;
        for (int r = 0; r < rows.size(); r++) {
            String rh = dim(rows.get(r).getHeight().getValue());
            rowIn[r] = rh == null || rh.endsWith("%") ? -1 : inches(rh);
            allRows &= rowIn[r] >= 0;
            sum += Math.max(0, rowIn[r]);
        }
        if (allRows && sum > 0 && tableHeight != null && !tableHeight.endsWith("%") && inches(tableHeight) > sum) {
            double scale = inches(tableHeight) / sum;
            for (int r = 0; r < rows.size(); r++) {
                rowIn[r] *= scale;
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            RowHandle row = rows.get(r);
            String height = dim(row.getHeight().getValue());
            html.append("<tr data-id=\"").append(row.getID()).append('"')
                    .append(css(row, true, false, height == null ? "" : "height:" + height + ";")).append('>');
            boolean first = true;
            for (Object o : row.getCells().getContents()) {
                CellHandle cell = (CellHandle) o;
                html.append("<td data-id=\"").append(cell.getID()).append('"');
                if (cell.getColumnSpan() > 1) {
                    html.append(" colspan=\"").append(cell.getColumnSpan()).append('"');
                }
                if (cell.getRowSpan() > 1) {
                    html.append(" rowspan=\"").append(cell.getRowSpan()).append('"');
                }
                String cellHeight = dim(cell.getHeight().getValue());
                html.append(css(cell, true, false, cellHeight == null ? "" : "height:" + cellHeight + ";")).append('>');
                // 칸 내용은 행 높이(칸 여백 뺀 만큼)를 넘으면 잘림
                double clip = 0;
                for (int k = r; k < Math.min(rows.size(), r + Math.max(1, cell.getRowSpan())); k++) {
                    clip = clip < 0 || rowIn[k] < 0 ? -1 : clip + rowIn[k];
                }
                String limit = clip <= 0 ? null : "calc(" + (long) Math.floor(clip * 96 + 1e-6) + "px - "
                        + length(nvl(cell.getStringProperty("paddingTop"))) + " - "
                        + length(nvl(cell.getStringProperty("paddingBottom"))) + " - " + borderPx(cell, "Top")
                        + " - " + borderPx(cell, "Bottom") + ")";
                if (cell.getContent().getCount() > 0) {
                    html.append("<div class=\"flow\"");
                    if (limit != null) {
                        html.append(" style=\"max-height:").append(limit).append(";overflow:hidden\"");
                    }
                    html.append('>');
                    children(cell.getContent());
                    html.append("</div>");
                } else if (rowTypes != null && first) {
                    // 표의 빈 행은 첫 칸에 행 종류를 흐리게 (이클립스처럼)
                    html.append("<div class=\"ph row-ph\"");
                    if (limit != null) {
                        html.append(" style=\"max-height:").append(limit).append(";overflow:hidden\"");
                    }
                    html.append('>').append(rowTypes.get(r)).append("</div>");
                } else {
                    html.append("<div class=\"empty\"");
                    if (limit != null) {
                        html.append(" style=\"height:min(13px, ").append(limit).append(")\"");
                    }
                    html.append("></div>");
                }
                first = false;
                html.append("</td>");
            }
            html.append("</tr>\n");
        }
        html.append("</tbody></table>\n");
    }

    private static List<RowHandle> rows(SlotHandle slot) {
        List<RowHandle> rows = new ArrayList<>();
        for (Object o : slot.getContents()) {
            rows.add((RowHandle) o);
        }
        return rows;
    }

    private static void addRows(List<RowHandle> rows, List<String> types, SlotHandle slot, String type) {
        for (RowHandle row : rows(slot)) {
            rows.add(row);
            types.add(type);
        }
    }

    private void image(ImageHandle h) {
        byte[] data = null;
        String src = null;
        String source = h.getSource();
        if ("embed".equals(source) && h.getEmbeddedImage() != null) {
            data = h.getEmbeddedImage().getData();
            if (data != null) {
                src = "data:image;base64," + Base64.getEncoder().encodeToString(data);
            }
        } else if ("file".equals(source) || "url".equals(source)) {
            File f = resource(unquote("file".equals(source) ? h.getFile() : h.getURI()));
            if (f != null) {
                data = read(f);
                src = f.toURI().toString();
            }
        }
        if (src == null) {
            // 불러올 수 없는 이미지(비었거나 스크립트로 정하는 주소)는 이클립스처럼 깨진 이미지 아이콘
            String what = h.getImageName() != null ? h.getImageName()
                    : "file".equals(source) ? h.getFile() : "url".equals(source) ? h.getURI() : h.getValueExpression();
            block(h, "<span class=\"img-broken\" title=\"" + esc(nvl(what)) + "\">×</span>");
            return;
        }
        // 크기를 안 정했으면 이미지 해상도대로 (72dpi 이미지는 화면에서 4/3배 — 이클립스와 같음)
        String style = size(h);
        String natural = style.isEmpty() ? naturalSize(data) : null;
        if (natural != null) {
            String[] wh = natural.split(" ");
            style = "width:" + wh[0] + ";height:" + wh[1] + ";";
        }
        block(h, "<img src=\"" + esc(src) + "\" style=\"" + style + "max-width:100%\">");
    }

    /** 리포트 폴더 기준 파일 (없으면 null) */
    private File resource(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(resourceDir, path.replaceFirst("^/", ""));
        }
        return f.isFile() ? f : null;
    }

    private static byte[] read(File f) {
        try {
            return Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** 이미지 원래 크기 "가로in 세로in" — 픽셀 ÷ 이미지에 적힌 DPI (없으면 96 = 화면 픽셀 그대로) */
    static String naturalSize(byte[] data) {
        if (data == null) {
            return null;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (in == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                double dpiX = 96;
                double dpiY = 96;
                IIOMetadata meta = reader.getImageMetadata(0);
                if (meta != null && meta.isStandardMetadataFormatSupported()) {
                    Node root = meta.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
                    dpiX = dpi(root, "HorizontalPixelSize", dpiX);
                    dpiY = dpi(root, "VerticalPixelSize", dpiY);
                }
                return fmt(reader.getWidth(0) / dpiX) + "in " + fmt(reader.getHeight(0) / dpiY) + "in";
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 표준 메타데이터의 픽셀 크기(mm) → DPI. 이상한 값이면 기본값 */
    private static double dpi(Node node, String name, double fallback) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (name.equals(n.getNodeName()) && n.getAttributes() != null && n.getAttributes().getNamedItem("value") != null) {
                double mm = Double.parseDouble(n.getAttributes().getNamedItem("value").getNodeValue());
                double dpi = mm > 0 ? 25.4 / mm : 0;
                return dpi >= 30 && dpi <= 1200 ? dpi : fallback;
            }
            double found = dpi(n, name, -1);
            if (found > 0) {
                return found;
            }
        }
        return fallback;
    }

    private static String size(ReportItemHandle h) {
        StringBuilder sb = new StringBuilder();
        String w = dim(h.getWidth().getValue());
        String hh = dim(h.getHeight().getValue());
        if (w != null) {
            sb.append("width:").append(w).append(';');
        }
        if (hh != null) {
            // 지정한 높이 그대로 (얇은 여백용 요소 등) — 기본 최소 높이 무시
            sb.append("height:").append(hh).append(";min-height:0;overflow:hidden;");
        }
        return sb.toString();
    }

    private static final Pattern VALUE_OF = Pattern.compile("(?is)<value-of\\b[^>]*>(.*?)</value-of>");

    /** 텍스트 요소 — HTML이면 그대로(값 넣기 태그는 글자로), 아니면 글자 그대로 */
    private static String text(String content, String type) {
        if (content == null) {
            return "";
        }
        boolean isHtml = "html".equals(type) || ("auto".equals(type) && content.trim().startsWith("<"));
        if (!isHtml) {
            return esc(content);
        }
        Matcher m = VALUE_OF.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(esc("<VALUE-OF>" + m.group(1) + "</VALUE-OF>")));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("(?is)<script\\b.*?</script>", "");
    }

    // ---------------------------------------------------------------- 스타일

    private String css(DesignElementHandle h, boolean box, boolean margin) {
        return css(h, box, margin, "");
    }

    /** 모델이 계산한 스타일 값(요소 → 스타일 → 선택자 → 상속)을 CSS로 */
    private String css(DesignElementHandle h, boolean box, boolean margin, String extra) {
        StringBuilder sb = new StringBuilder(extra);
        add(sb, h, TEXT_PROPS);
        if (box) {
            add(sb, h, BOX_PROPS);
            background(sb, h);
            for (String side : SIDES) {
                String style = h.getStringProperty("border" + side + "Style");
                if (style != null && !"none".equals(style)) {
                    String width = isSet(h, "border" + side + "Width") ? h.getStringProperty("border" + side + "Width") : null;
                    String color = h.getStringProperty("border" + side + "Color");
                    sb.append("border-").append(side.toLowerCase()).append(':')
                            .append(borderWidth(width)).append(' ').append(style).append(' ')
                            .append(color == null ? "black" : color).append(';');
                }
            }
            String decoration = "";
            if ("underline".equals(h.getStringProperty("textUnderline"))) {
                decoration += " underline";
            }
            if ("line-through".equals(h.getStringProperty("textLineThrough"))) {
                decoration += " line-through";
            }
            if ("overline".equals(h.getStringProperty("textOverline"))) {
                decoration += " overline";
            }
            if (!decoration.isEmpty()) {
                sb.append("text-decoration:").append(decoration.trim()).append(';');
            }
        }
        if (margin) {
            // 이클립스 디자이너는 여백을 지정하지 않은 요소를 위·왼쪽 3px, 아래·오른쪽 2px 띄워 그림
            //   (칸·본문 선 안쪽 4px, 위아래 요소의 선 사이 6px, 오른쪽 칸 선까지 3px — 캡처 측정값)
            for (int i = 0; i < MARGIN_PROPS.length; i += 2) {
                if (h.getPropertyDefn(MARGIN_PROPS[i]) != null) {
                    String fallback = MARGIN_PROPS[i].equals("marginTop") || MARGIN_PROPS[i].equals("marginLeft") ? "3px" : "2px";
                    sb.append(MARGIN_PROPS[i + 1]).append(':')
                            .append(isSet(h, MARGIN_PROPS[i]) ? length(h.getStringProperty(MARGIN_PROPS[i])) : fallback).append(';');
                }
            }
            if (h instanceof ReportItemHandle && !(h instanceof GridHandle) && !(h instanceof TableHandle)
                    && !(h instanceof ImageHandle)) {
                sb.append(size((ReportItemHandle) h));
            }
        }
        return sb.length() == 0 ? "" : " style=\"" + esc(sb.toString()) + "\"";
    }

    /** 요소 자신이나 그 스타일에 값이 있는지 (모델 기본값이 아닌지) */
    private static boolean isSet(DesignElementHandle h, String prop) {
        PropertyHandle own = h.getPropertyHandle(prop);
        if (own != null && own.isSet()) {
            return true;
        }
        DesignElementHandle style = h.getStyle();
        PropertyHandle styled = style == null ? null : style.getPropertyHandle(prop);
        return styled != null && styled.isSet();
    }

    /** 배경 이미지 — 리포트 폴더 기준 파일(예: lib/CntrWaterMark30.png)이나 리포트에 넣은 이미지 */
    private void background(StringBuilder sb, DesignElementHandle h) {
        if (h.getPropertyDefn("backgroundImage") == null) {
            return;
        }
        String image = unquote(h.getStringProperty("backgroundImage"));
        if (image == null || image.isEmpty()) {
            return;
        }
        String url = null;
        byte[] data = null;
        if (h.getModuleHandle().findImage(image) != null) {
            data = h.getModuleHandle().findImage(image).getData(h.getModuleHandle().getModule());
            if (data != null) {
                url = "data:image;base64," + Base64.getEncoder().encodeToString(data);
            }
        } else {
            File f = resource(image);
            if (f != null) {
                data = read(f);
                url = f.toURI().toString();
            }
        }
        if (url == null) {
            return;
        }
        sb.append("background-image:url('").append(url).append("');");
        String repeat = h.getStringProperty("backgroundRepeat");
        sb.append("background-repeat:").append(repeat == null ? "repeat" : repeat).append(';');
        String x = h.getStringProperty("backgroundPositionX");
        String y = h.getStringProperty("backgroundPositionY");
        sb.append("background-position:").append(x == null ? "0" : x).append(' ').append(y == null ? "0" : y).append(';');
        String w = h.getPropertyDefn("backgroundSizeWidth") == null || !isSet(h, "backgroundSizeWidth") ? null
                : h.getStringProperty("backgroundSizeWidth");
        String hh = h.getPropertyDefn("backgroundSizeHeight") == null || !isSet(h, "backgroundSizeHeight") ? null
                : h.getStringProperty("backgroundSizeHeight");
        if (w != null || hh != null) {
            sb.append("background-size:").append(w == null ? "auto" : w).append(' ').append(hh == null ? "auto" : hh).append(';');
        } else if (naturalSize(data) != null) {
            // 크기를 안 정했으면 이미지 해상도대로 (예: 72dpi 로고는 화면에서 4/3배 — 이클립스와 같음)
            sb.append("background-size:").append(naturalSize(data)).append(';');
        }
    }

    private static void add(StringBuilder sb, DesignElementHandle h, String[] props) {
        for (int i = 0; i < props.length; i += 2) {
            if (h.getPropertyDefn(props[i]) == null) {
                continue;
            }
            String v = h.getStringProperty(props[i]);
            if (v == null || v.isEmpty()) {
                continue;
            }
            if ("fontSize".equals(props[i])) {
                v = fontSize(v);
            } else if ("whiteSpace".equals(props[i]) && "normal".equals(v)) {
                continue;
            } else if (props[i].startsWith("padding") || props[i].startsWith("margin")) {
                v = length(v);
            }
            sb.append(props[i + 1]).append(':').append(v).append(';');
        }
    }

    /** 그려지는 테두리 두께 (없으면 0px) */
    private static String borderPx(DesignElementHandle h, String side) {
        String style = h.getStringProperty("border" + side + "Style");
        if (style == null || "none".equals(style)) {
            return "0px";
        }
        return borderWidth(isSet(h, "border" + side + "Width") ? h.getStringProperty("border" + side + "Width") : null);
    }

    /** 테두리 두께 — 이클립스 디자이너처럼 thin 1px, medium(지정 안 함 포함) 2px, thick 3px (2px 이중선은 굵은 한 줄로 보임) */
    private static String borderWidth(String width) {
        if (width == null || "medium".equals(width)) {
            return "2px";
        }
        if ("thin".equals(width)) {
            return "1px";
        }
        return "thick".equals(width) ? "3px" : width;
    }

    /** BIRT 글꼴 크기 이름 → pt (BIRT 기본 medium = 10pt) */
    private static String fontSize(String v) {
        switch (v) {
            case "xx-small":
                return "7pt";
            case "x-small":
                return "8pt";
            case "small":
                return "9pt";
            case "medium":
                return "10pt";
            case "large":
                return "12pt";
            case "x-large":
                return "14pt";
            case "xx-large":
                return "18pt";
            default:
                return v;
        }
    }

    // ---------------------------------------------------------------- 도우미

    private static String dim(Object v) {
        if (v instanceof DimensionValue) {
            DimensionValue d = (DimensionValue) v;
            String units = d.getUnits() == null ? "in" : d.getUnits();
            String px = px(d.getMeasure(), units);
            return px != null ? px : fmt(d.getMeasure()) + units;
        }
        return v == null ? null : v.toString();
    }

    /**
     * 길이 → 정수 px (화면 96dpi). 이클립스 디자이너처럼 소수점을 버림 — 0.1in는 9px, 여백 1pt는 1px.
     * 소수점을 남기면 요소마다 1px 가까이 밀려서 아래로 갈수록 어긋남. %·em 등은 null
     */
    private static String px(double measure, String units) {
        double perUnit;
        switch (units) {
            case "in":
                perUnit = 96;
                break;
            case "cm":
                perUnit = 96 / 2.54;
                break;
            case "mm":
                perUnit = 96 / 25.4;
                break;
            case "pt":
                perUnit = 96 / 72.0;
                break;
            case "pc":
                perUnit = 16;
                break;
            case "px":
                perUnit = 1;
                break;
            default:
                return null;
        }
        return (long) Math.floor(measure * perUnit + 1e-6) + "px";
    }

    /** 모델의 길이 글자("1pt", "0.25in")를 정수 px로 — 못 바꾸면 그대로 */
    private static String length(String v) {
        try {
            DimensionValue d = DimensionValue.parse(v);
            String px = d == null || d.getUnits() == null ? null : px(d.getMeasure(), d.getUnits());
            return px != null ? px : v;
        } catch (Exception e) {
            return v;
        }
    }

    private static String dim(org.eclipse.birt.report.model.api.DimensionHandle h) {
        return h == null ? null : dim(h.getValue());
    }

    private static double inches(String d) {
        DimensionValue v;
        try {
            v = DimensionValue.parse(d);
        } catch (Exception e) {
            return 0;
        }
        if (v == null || v.getUnits() == null) {
            return v == null ? 0 : v.getMeasure();
        }
        switch (v.getUnits()) {
            case "pt":
                return v.getMeasure() / 72;
            case "cm":
                return v.getMeasure() / 2.54;
            case "mm":
                return v.getMeasure() / 25.4;
            case "px":
                return v.getMeasure() / 96;
            default:
                return v.getMeasure();
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 10000) / 10000.0);
    }

    private static String unquote(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
