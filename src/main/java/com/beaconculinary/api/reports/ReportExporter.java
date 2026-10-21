package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reports R1 §1.6: CSV and XLSX built from the envelope the screen shows, so exported figures
 * always match the on-screen ones. Exports above the row cap are refused rather than silently
 * truncated.
 */
@Component
public class ReportExporter {
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final int maxRows;

    public ReportExporter(@Value("${reports.export.max-rows:50000}") int maxRows) {
        this.maxRows = maxRows;
    }

    public enum Format {
        CSV("csv", "text/csv; charset=UTF-8"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }
    }

    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    public ExportFile export(ReportEnvelope envelope, Format format) {
        if (envelope.rows() != null && envelope.rows().size() > maxRows) {
            throw ReportParamException.of("export", "This export has " + envelope.rows().size()
                    + " rows; the limit is " + maxRows + ". Narrow the date range.");
        }
        var content = envelope.sections() != null ? statementXlsx(envelope)
                : format == Format.CSV ? csv(envelope) : xlsx(envelope);
        return new ExportFile(filename(envelope.meta()) + "." + format.extension, format.contentType, content);
    }

    /** key_from_to for ranged reports; otherwise key plus the set parameter values, e.g.
     * x-reading_2026-10-05 or z-reading_1. */
    private static String filename(ReportEnvelope.Meta meta) {
        var params = meta.params();
        if (params.containsKey("from") && params.containsKey("to")) {
            return meta.key() + "_" + params.get("from") + "_" + params.get("to");
        }
        var name = new StringBuilder(meta.key());
        params.values().stream().filter(java.util.Objects::nonNull).forEach(value -> name.append('_').append(value));
        return name.toString();
    }

    // Statement XLSX (R3): the title block, then every section stacked in order (key-value
    // sections as label/value pairs, tables with their header and totals, signature lines),
    // then the Checks sheet.
    private byte[] statementXlsx(ReportEnvelope envelope) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var styles = new Styles(workbook);
            var meta = envelope.meta();
            var sheet = workbook.createSheet("Report");

            var r = 0;
            var title = meta.statementTitle() != null ? meta.statementTitle() : meta.title();
            textCell(sheet.createRow(r++), 0, title + (meta.reference() == null ? "" : " " + meta.reference())
                    + (Boolean.TRUE.equals(meta.interim()) ? " (INTERIM)" : ""), styles.title);
            if (meta.business() != null) {
                textCell(sheet.createRow(r++), 0, meta.business().name(), null);
            }
            textCell(sheet.createRow(r++), 0, "Parameters: " + describe(meta.params(), meta.paramLabels()), null);
            textCell(sheet.createRow(r++), 0, "Generated: "
                    + LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(meta.generatedAt(), ClockConfig.BUSINESS_ZONE))
                    + " (" + meta.timezone() + ")", null);
            textCell(sheet.createRow(r++), 0, "Amounts include VAT", null);

            var width = 2;
            for (var section : envelope.sections()) {
                r++;
                var sectionTitle = (String) section.get("title");
                if (sectionTitle != null) {
                    textCell(sheet.createRow(r++), 0, sectionTitle, styles.header);
                }
                switch (String.valueOf(section.get("type"))) {
                    case "KEY_VALUE" -> {
                        for (var item : list(section.get("items"))) {
                            var map = map(item);
                            var row = sheet.createRow(r++);
                            var total = "TOTAL".equals(map.get("emphasis"));
                            textCell(row, 0, String.valueOf(map.get("label")), total ? styles.totalText : null);
                            var column = new ReportColumn("value", "", ColumnType.valueOf(String.valueOf(map.get("type"))), null, null);
                            writeRow(row, 1, List.of(column), Map.of("value", map.get("value") == null ? "" : map.get("value")),
                                    styles, total);
                        }
                    }
                    case "TABLE" -> {
                        var columns = list(section.get("columns")).stream().map(ReportExporter::column).toList();
                        width = Math.max(width, columns.size());
                        var header = sheet.createRow(r++);
                        for (var c = 0; c < columns.size(); c++) {
                            textCell(header, c, columns.get(c).label(), styles.header);
                        }
                        for (var row : list(section.get("rows"))) {
                            writeRow(sheet.createRow(r++), 0, columns, map(row), styles, false);
                        }
                        var totals = section.get("totals");
                        if (totals != null && !map(totals).isEmpty()) {
                            writeRow(sheet.createRow(r++), 0, columns, map(totals), styles, true);
                        }
                    }
                    case "SIGNATURE_LINES" -> {
                        for (var label : list(section.get("labels"))) {
                            var row = sheet.createRow(r++);
                            textCell(row, 0, label + ":", null);
                            textCell(row, 1, "______________________________", null);
                            r++;
                        }
                    }
                    default -> {
                    }
                }
            }
            for (var c = 0; c < width; c++) {
                sheet.setColumnWidth(c, (c == 0 ? 32 : 18) * 256);
            }

            writeChecks(workbook.createSheet("Checks"), envelope.checks(), styles);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** A section column: a ReportColumn when built live, a plain map when read from a Z snapshot. */
    private static ReportColumn column(Object value) {
        if (value instanceof ReportColumn column) {
            return column;
        }
        var map = map(value);
        return new ReportColumn((String) map.get("key"), (String) map.get("label"), ColumnType.valueOf((String) map.get("type")),
                null, null);
    }

    // CSV: UTF-8 with BOM, comma separator, decimal point, ISO dates; header and data rows only.
    private byte[] csv(ReportEnvelope envelope) {
        var out = new StringBuilder("﻿");
        out.append(envelope.columns().stream().map(column -> csvField(column.label())).collect(Collectors.joining(","))).append("\r\n");
        for (var row : envelope.rows()) {
            out.append(envelope.columns().stream().map(column -> csvField(csvValue(row.get(column.key()))))
                    .collect(Collectors.joining(","))).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // XLSX: title block, typed data cells, a totals row, and a second sheet with the checks.
    private byte[] xlsx(ReportEnvelope envelope) {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var styles = new Styles(workbook);
            var meta = envelope.meta();
            var sheet = workbook.createSheet("Report");

            var r = 0;
            textCell(sheet.createRow(r++), 0, meta.title(), styles.title);
            textCell(sheet.createRow(r++), 0, "Parameters: " + describe(meta.params(), meta.paramLabels()), null);
            textCell(sheet.createRow(r++), 0, "Generated: "
                    + LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(meta.generatedAt(), ClockConfig.BUSINESS_ZONE))
                    + " (" + meta.timezone() + ")", null);
            textCell(sheet.createRow(r++), 0, "Amounts include VAT", null);
            r++;

            var header = sheet.createRow(r++);
            var columns = envelope.columns();
            for (var c = 0; c < columns.size(); c++) {
                textCell(header, c, columns.get(c).label(), styles.header);
            }
            for (var row : envelope.rows()) {
                writeRow(sheet.createRow(r++), columns, row, styles, false);
            }
            if (envelope.totals() != null && !envelope.totals().isEmpty()) {
                writeRow(sheet.createRow(r), columns, envelope.totals(), styles, true);
            }
            for (var c = 0; c < columns.size(); c++) {
                sheet.setColumnWidth(c, 18 * 256);
            }

            writeChecks(workbook.createSheet("Checks"), envelope.checks(), styles);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeRow(Row target, List<ReportColumn> columns, Map<String, Object> values, Styles styles, boolean totals) {
        writeRow(target, 0, columns, values, styles, totals);
    }

    private void writeRow(Row target, int firstColumn, List<ReportColumn> columns, Map<String, Object> values, Styles styles,
                          boolean totals) {
        for (var c = 0; c < columns.size(); c++) {
            var column = columns.get(c);
            var value = typed(column, values.get(column.key()));
            if (value == null) {
                continue;
            }
            var cell = target.createCell(firstColumn + c);
            if (value instanceof BigDecimal decimal) {
                var percent = column.type() == ColumnType.PERCENT;
                cell.setCellValue(percent ? decimal.movePointLeft(2).doubleValue() : decimal.doubleValue());
                cell.setCellStyle(percent ? styles.percent(totals)
                        : column.type() == ColumnType.DECIMAL ? styles.decimal(totals) : styles.money(totals));
            } else if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(styles.integer(totals));
            } else if (value instanceof LocalDate date) {
                cell.setCellValue(date);
                cell.setCellStyle(styles.date(totals));
            } else if (value instanceof Instant instant) {
                cell.setCellValue(LocalDateTime.ofInstant(instant, ClockConfig.BUSINESS_ZONE));
                cell.setCellStyle(styles.dateTime(totals));
            } else {
                cell.setCellValue(value.toString());
                if (totals) {
                    cell.setCellStyle(styles.totalText);
                }
            }
        }
    }

    /** Values read back from a Z snapshot are JSON text: dates and times arrive as strings. */
    private static Object typed(ReportColumn column, Object value) {
        if (value instanceof String text && !text.isBlank()) {
            try {
                if (column.type() == ColumnType.DATE) {
                    return LocalDate.parse(text);
                }
                if (column.type() == ColumnType.DATETIME) {
                    return Instant.parse(text);
                }
            } catch (java.time.format.DateTimeParseException e) {
                return value; // a label such as "Total" in a dated column
            }
        }
        return value;
    }

    private void writeChecks(Sheet sheet, List<ReportCheck> checks, Styles styles) {
        var labels = List.of("Check", "Status", "Expected", "Actual", "Difference", "Detail");
        var header = sheet.createRow(0);
        for (var c = 0; c < labels.size(); c++) {
            textCell(header, c, labels.get(c), styles.header);
        }
        var r = 1;
        for (var check : checks) {
            var row = sheet.createRow(r++);
            textCell(row, 0, check.label(), null);
            textCell(row, 1, check.status().name(), null);
            numberCell(row, 2, check.expected(), styles.money(false));
            numberCell(row, 3, check.actual(), styles.money(false));
            numberCell(row, 4, check.difference(), styles.money(false));
            if (check.detail() != null) {
                textCell(row, 5, check.detail(), null);
            }
        }
        sheet.setColumnWidth(0, 60 * 256);
        for (var c = 1; c < 5; c++) {
            sheet.setColumnWidth(c, 14 * 256);
        }
        sheet.setColumnWidth(5, 50 * 256);
    }

    /** ID parameters show their option label (accountId=Acme Logistics), not the raw id. */
    private static String describe(Map<String, Object> params, Map<String, String> labels) {
        return params.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + "=" + labels.getOrDefault(entry.getKey(), entry.getValue().toString()))
                .collect(Collectors.joining(", "));
    }

    private static void textCell(Row row, int column, String value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void numberCell(Row row, int column, BigDecimal value, CellStyle style) {
        if (value != null) {
            var cell = row.createCell(column);
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        }
    }

    /** Cell styles, created once per workbook (POI caps the number of styles). */
    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle totalText;
        private final CellStyle[] money = new CellStyle[2];
        private final CellStyle[] percent = new CellStyle[2];
        private final CellStyle[] integer = new CellStyle[2];
        private final CellStyle[] date = new CellStyle[2];
        private final CellStyle[] dateTime = new CellStyle[2];
        private final CellStyle[] decimal = new CellStyle[2];

        Styles(Workbook workbook) {
            var format = workbook.createDataFormat();
            var bold = workbook.createFont();
            bold.setBold(true);
            var titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setBorderBottom(BorderStyle.THIN);
            totalText = workbook.createCellStyle();
            totalText.setFont(bold);
            totalText.setBorderTop(BorderStyle.THIN);

            var formats = new String[]{"\"R\" #,##0.00;-\"R\" #,##0.00", "0.00%", "0", "yyyy-mm-dd", "yyyy-mm-dd hh:mm", "0.00"};
            var targets = new CellStyle[][]{money, percent, integer, date, dateTime, decimal};
            for (var i = 0; i < formats.length; i++) {
                for (var t = 0; t < 2; t++) {
                    var style = workbook.createCellStyle();
                    style.setDataFormat(format.getFormat(formats[i]));
                    if (t == 1) {
                        style.setFont(bold);
                        style.setBorderTop(BorderStyle.THIN);
                    }
                    targets[i][t] = style;
                }
            }
        }

        CellStyle money(boolean totals) {
            return money[totals ? 1 : 0];
        }

        CellStyle percent(boolean totals) {
            return percent[totals ? 1 : 0];
        }

        CellStyle integer(boolean totals) {
            return integer[totals ? 1 : 0];
        }

        CellStyle date(boolean totals) {
            return date[totals ? 1 : 0];
        }

        CellStyle dateTime(boolean totals) {
            return dateTime[totals ? 1 : 0];
        }

        CellStyle decimal(boolean totals) {
            return decimal[totals ? 1 : 0];
        }
    }
}
