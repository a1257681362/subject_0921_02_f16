package com.evops.apartment.report;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGridCol;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVerticalJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;

import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Paths;

/**
 * 仅用于一次性生成 poi-tl 模板（不属于交付代码）。运行 main 把 docx 写入
 * src/main/resources/templates/sign-result.docx。用 POI API 产出规范 OOXML，
 * 保证循环数据行内的子标签被 LoopRowTableRenderPolicy 正确归属（不会被当顶层模板提前清空）。
 *
 * 用完即删；模板文件本身随包发布。
 */
public final class SignTemplateTool {
    private SignTemplateTool() {
    }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0]
                : Paths.get("src/main/resources/templates/sign-result.docx").toAbsolutePath().toString();
        try (XWPFDocument doc = new XWPFDocument()) {
            buildHeader(doc);
            buildBody(doc);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.write(fos);
            }
        }
        System.out.println("wrote " + out);
    }

    private static void buildHeader(XWPFDocument doc) {
        XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc);
        XWPFParagraph hp = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT).getParagraphArray(0);
        if (hp == null) {
            hp = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT).createParagraph();
        }
        // 页眉下边框
        CTP ctp = hp.getCTP();
        CTPPr ppr = ctp.isSetPPr() ? ctp.getPPr() : ctp.addNewPPr();
        CTPBdr bdr = ppr.isSetPBdr() ? ppr.getPBdr() : ppr.addNewPBdr();
        bdr.addNewBottom().setVal(STBorder.SINGLE);
        bdr.getBottom().setSz(BigInteger.valueOf(6));
        bdr.getBottom().setSpace(BigInteger.ONE);
        bdr.getBottom().setColor("808080");
        XWPFRun r = hp.createRun();
        r.setBold(true);
        r.setFontSize(10);
        r.setText("公寓楼：{{buildingName}}");
    }

    private static void buildBody(XWPFDocument doc) {
        XWPFParagraph title = doc.createParagraph();
        title.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun tr = title.createRun();
        tr.setBold(true);
        tr.setFontSize(18);
        tr.setText("签约结果报告");

        line(doc, "公寓楼名称：{{buildingName}}");
        line(doc, "统计小区：{{community}}");
        line(doc, "起租日期区间：{{period}}");
        line(doc, "生成时间：{{generatedAt}}");
        line(doc, "经办人：{{operator}}");
        line(doc, "");

        XWPFParagraph h = doc.createParagraph();
        XWPFRun hr = h.createRun();
        hr.setBold(true);
        hr.setFontSize(12);
        hr.setText("一、本期签约租约明细");

        String[] headers = {"序号", "合同号", "房源编号", "房号", "租客姓名",
                "起租日期", "到期日期", "月租金(元)", "押金(元)"};
        // poi-tl LoopRow（onSameLine）：触发标签 {{leases}} 与数据行同一行，行内字段用 [字段]；
        // 序号格在触发标签后追加 [seq]（{{leases}} 渲染时被清空，留下序号）。
        String[] fields = {"{{leases}}[seq]", "[contractNo]", "[propertyCode]", "[roomNo]",
                "[tenantName]", "[leaseStart]", "[leaseEnd]", "[monthlyRent]", "[deposit]"};
        XWPFTable table = doc.createTable(3, headers.length);
        fixGrid(table);
        for (int i = 0; i < headers.length; i++) {
            setCell(table.getRow(0).getCell(i), headers[i], true);
            setCell(table.getRow(1).getCell(i), fields[i], false);
        }
        // 合计行（不做跨列合并，避免不同查看器对 hMerge 兼容差异）
        XWPFTableRow total = table.getRow(2);
        setCell(total.getCell(0), "合计", true);
        setCell(total.getCell(1), "本期签约共 {{leaseCount}} 条", false);
        for (int i = 2; i <= 5; i++) {
            setCell(total.getCell(i), "", false);
        }
        setCell(total.getCell(6), "租金合计（元）", true);
        setCell(total.getCell(7), "{{totalRent}}", true);
        setCell(total.getCell(8), "", false);

        line(doc, "");
        XWPFParagraph note = doc.createParagraph();
        XWPFRun nr = note.createRun();
        nr.setFontSize(9);
        nr.setText("注：租金合计由上表租约明细的月租金自动汇总；本期无签约明细时合计为 0。");
    }

    private static void line(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(text);
    }

    private static void setCell(XWPFTableCell cell, String text, boolean bold) {
        // 重建单元格内唯一规范段落，去掉 createTable 自带空段
        CTTc ctTc = cell.getCTTc();
        for (int i = ctTc.getPList().size() - 1; i >= 0; i--) {
            ctTc.removeP(i);
        }
        CTP ctp = ctTc.addNewP();
        XWPFParagraph p = new XWPFParagraph(ctp, cell);
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = p.createRun();
        run.setBold(bold);
        run.setFontSize(10);
        run.setText(text);
        CTTcPr pr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();
        if (!pr.isSetVAlign()) {
            CTVerticalJc v = pr.addNewVAlign();
            v.setVal(STVerticalJc.CENTER);
        }
    }

    private static void fixGrid(XWPFTable table) {
        CTTbl tbl = table.getCTTbl();
        CTTblPr pr = tbl.getTblPr();
        CTTblWidth width = pr.isSetTblW() ? pr.getTblW() : pr.addNewTblW();
        width.setW(BigInteger.valueOf(9000));
        width.setType(STTblWidth.DXA);
        CTTblGrid grid = CTTblGrid.Factory.newInstance();
        int[] cols = {700, 1500, 1300, 900, 1100, 1200, 1200, 1100, 1000};
        for (int c : cols) {
            CTTblGridCol gc = grid.addNewGridCol();
            gc.setW(BigInteger.valueOf(c));
        }
        tbl.setTblGrid(grid);
    }
}
