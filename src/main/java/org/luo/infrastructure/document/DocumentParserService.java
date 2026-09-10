package org.luo.infrastructure.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.luo.service.KbService;

/**
 * 知识库文件解析：把上传的文档（txt/md/csv 等纯文本 + pdf/docx/xlsx）抽取为纯文本，
 * 交由 {@link KbService} 自动分块 + 向量化入库。
 * <p>
 * 支持的类型与解析方式：
 * <ul>
 *   <li>{@code .txt / .md / .csv / .json / .xml / .yml / .properties / .log}：按文本解码（UTF-8 优先，非法字节回退 GBK）；</li>
 *   <li>{@code .pdf}：Apache PDFBox 文本抽取；</li>
 *   <li>{@code .docx}：POI XWPF 抽取段落 + 表格行；</li>
 *   <li>{@code .xlsx}：POI XSSF 逐工作表逐行取值（单元格经 {@link DataFormatter} 格式化为文本）。</li>
 * </ul>
 * 不支持的扩展名 / 超限文件 / 抽不出文本的文件会抛出 {@link AiBusinessException}（由调用方逐文件隔离处理）。
 */
@Slf4j
@Service
public class DocumentParserService {

    /** 单文件大小上限（字节）：超出直接拒绝，避免大文件撑爆内存与 embedding 请求。 */
    private static final long MAX_FILE_BYTES = 15 * 1024 * 1024L;

    /** 单文件抽取文本上限（字符）：超出截断（按块量也受该上限约束）。 */
    private static final int MAX_TEXT_CHARS = 500_000;

    /** 文件名大小写归一化后的扩展名集合 → 走纯文本解码的格式。 */
    private static final List<String> TEXT_EXTS = List.of(
            "txt", "md", "markdown", "csv", "json", "xml", "yml", "yaml", "properties", "log", "sql");

    private static final DataFormatter DATA_FORMATTER = new DataFormatter(Locale.ROOT);

    /**
     * 解析单个上传文件为纯文本（已去除首尾空白、限制最大字符数）。
     *
     * @throws AiBusinessException 不支持的类型 / 文件为空或超限 / 提取不到文本时抛出
     */
    public String parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "上传文件为空");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extOf(name);
        if (ext.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "无法识别文件类型（无扩展名）：" + name + "，支持 txt/md/csv/pdf/docx/xlsx");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "读取文件失败：" + name);
        }
        if (bytes.length == 0) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "文件内容为空：" + name);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "文件超过大小上限（" + (MAX_FILE_BYTES / 1024 / 1024) + "MB）：" + name);
        }
        String text;
        try {
            if (TEXT_EXTS.contains(ext)) {
                text = decodeText(bytes);
            } else if ("pdf".equals(ext)) {
                text = parsePdf(bytes);
            } else if ("docx".equals(ext)) {
                text = parseDocx(bytes);
            } else if ("xlsx".equals(ext)) {
                text = parseXlsx(bytes);
            } else {
                throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                        "不支持的文件类型 ." + ext + "：" + name + "，支持 txt/md/csv/pdf/docx/xlsx");
            }
        } catch (AiBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("文档解析失败：{}（{}）", name, e.getMessage());
            throw new AiBusinessException(AiErrorCode.INTERNAL_ERROR, "文档解析失败：" + name + "（" + e.getMessage() + "）");
        }
        if (text == null || text.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "未能从文件中提取到文本内容：" + name);
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_TEXT_CHARS) {
            log.warn("文档文本超长（{} 字符），已截断至 {} 字符：{}", trimmed.length(), MAX_TEXT_CHARS, name);
            trimmed = trimmed.substring(0, MAX_TEXT_CHARS);
        }
        return trimmed;
    }

    /** 取小写扩展名（不含点）；无扩展名返回空串。 */
    private static String extOf(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    // ==================== 各格式解析 ====================

    /** 纯文本解码：UTF-8 严格校验，非法字节序列回退 GBK（中文 Windows 常见编码）。 */
    private static String decodeText(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /** PDF：PDFBox 全页文本抽取。 */
    private static String parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** docx：正文段落 + 表格行（表格内多列以「 | 」连接成一行）。 */
    private static String parseDocx(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> lines = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) lines.add(t.strip());
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String t = cell.getText();
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(t == null ? "" : t.strip());
                    }
                    if (sb.length() > 0) lines.add(sb.toString());
                }
            }
            return String.join("\n", lines);
        }
    }

    /** xlsx：逐工作表、逐行读取单元格文本（DataFormatter 统一转字符串，保证数字/日期可读）。 */
    private static String parseXlsx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                if (sheet == null) continue;
                sb.append("【").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    boolean any = false;
                    for (Cell cell : row) {
                        String t = DATA_FORMATTER.formatCellValue(cell);
                        if (line.length() > 0) line.append(" | ");
                        line.append(t);
                        any = true;
                    }
                    if (any) sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
