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
        if (envelope.rows().size() > maxRows) {
            throw ReportParamException.of("export", "This export has " + envelope.rows().size()
                    + " rows; the limit is " + maxRows + ". Narrow the date range.");
        }
        var params = envelope.meta().params();
        var filename = envelope.meta().key() + "_" + params.get("from") + "_" + params.get("to") + "." + format.extension;
        var content = format == Format.CSV ? csv(envelope) : xlsx(envelope);
        return new ExportFile(filename, format.contentType, content);
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
            textCell(sheet.createRow(r++), 0, "Parameters: " + describe(meta.params()), null);
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
        for (var c = 0; c < columns.size(); c++) {
            var column = columns.get(c);
            var value = values.get(column.key());
            if (value == null) {
                continue;
            }
            var cell = target.createCell(c);
            if (value instanceof BigDecimal decimal) {
                var percent = column.type() == ColumnType.PERCENT;
                cell.setCellValue(percent ? decimal.movePointLeft(2).doubleValue() : decimal.doubleValue());
                cell.setCellStyle(percent ? styles.percent(totals) : styles.money(totals));
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

    private static String describe(Map<String, Object> params) {
        return params.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
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

            var formats = new String[]{"\"R\" #,##0.00;-\"R\" #,##0.00", "0.00%", "0", "yyyy-mm-dd", "yyyy-mm-dd hh:mm"};
            var targets = new CellStyle[][]{money, percent, integer, date, dateTime};
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
    }
}
