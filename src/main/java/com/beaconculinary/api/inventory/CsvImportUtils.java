package com.beaconculinary.api.inventory;

import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Shared line-reading/cell-splitting for the CSV bulk-import endpoints (ingredients, GRV).
 * Column matching stays per-feature since each sheet has different required headers. */
final class CsvImportUtils {
    private CsvImportUtils() {
    }

    static List<String> readNonBlankLines(MultipartFile file) {
        try (var reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(CsvImportUtils::stripLeadingBom)
                    .filter(line -> !line.isBlank())
                    .toList();
        } catch (IOException e) {
            throw new BulkImportException(List.of("Could not read CSV file: " + e.getMessage()));
        }
    }

    private static String stripLeadingBom(String line) {
        return !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
    }

    static String detectDelimiter(String headerLine) {
        return headerLine.contains("\t") && !headerLine.contains(",") ? "\t" : ",";
    }

    /** Uppercases and strips everything but letters, so "Cost Per Unit", "COST_PER_UNIT" and
     * "cost-per-unit" all normalize to the same key for header matching. */
    static String normalizeHeaderCell(String cell) {
        return cell.trim().toUpperCase().replaceAll("[^A-Z]", "");
    }

    static String cellAt(List<String> cells, Integer index) {
        if (index == null || index >= cells.size()) {
            return null;
        }
        return cells.get(index).trim();
    }

    /** Minimal CSV cell splitter — handles double-quoted fields (with "" as an escaped quote)
     * so values containing the delimiter still parse correctly. */
    static List<String> splitCsvLine(String line, String delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char delimChar = delimiter.charAt(0);

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimChar) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }
}
