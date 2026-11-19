package com.beaconculinary.api.locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-2: golden outputs. Each output is recorded once on the code before the change (run with
 * {@code -Dgolden.record=true}) into {@code src/test/resources/golden/t0-2/}, and compared
 * afterwards. Two things are rewritten, because they differ between runs of the same code:
 * database ids (a known id becomes its fixture name, {@code <s1>}, {@code <o5>}, {@code <pay1>},
 * as an {@code id}/{@code ...Id}/whole-number {@code value}, in an {@code /admin/...} path, after
 * a {@code #}, as a digits-only CSV field or as a whole-number xlsx cell; test ids are in the tens of
 * thousands, so no amount or order number is mistaken for one), JSON key order (sorted), and
 * times the database stamps with the real time ({@code signedOffAt}).
 * Everything else (money text, dates, times, the zone, the currency, generatedAt) is compared
 * exactly; CSV is otherwise compared byte for byte.
 */
final class Golden {
    private static final Path DIR = Path.of("src", "test", "resources", "golden", "t0-2");
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern PATH_NUMBER = Pattern.compile("(?<=/)(\\d+)(?=/|$|\\?)");
    private static final Pattern HASH_NUMBER = Pattern.compile("#(\\d+)");
    /** Times the database stamps with SYSUTCDATETIME() when the row is written (the real time,
     * not the test clock), so they differ on every run. */
    private static final java.util.Set<String> DATABASE_STAMPED = java.util.Set.of("signedOffAt");
    /** A whole CSV field of digits only. */
    private static final Pattern CSV_NUMBER = Pattern.compile("(?m)(?<=^|,)(\\d+)(?=,|\\r?$)");

    private final Map<Long, String> names = new LinkedHashMap<>();

    Golden name(long id, String name) {
        names.putIfAbsent(id, "<" + name + ">");
        return this;
    }

    static boolean recording() {
        return Boolean.getBoolean("golden.record");
    }

    /** A JSON response, ids replaced, keys sorted, pretty-printed. */
    void json(String file, String body) throws IOException {
        // Jackson's pretty printer uses the platform line separator; the golden files use \n.
        compare(file, MAPPER.writeValueAsString(normalise(MAPPER.readTree(body), null)).replace("\r\n", "\n") + "\n");
    }

    /** A CSV export, byte for byte apart from ids in id columns (none of the chosen exports have any). */
    void text(String file, String body) throws IOException {
        var matcher = CSV_NUMBER.matcher(hashIds(body));
        var out = new StringBuilder();
        while (matcher.find()) {
            var name = names.get(Long.parseLong(matcher.group(1)));
            matcher.appendReplacement(out, name == null ? matcher.group(1) : name);
        }
        matcher.appendTail(out);
        compare(file, out.toString());
    }

    /** "Payment #18463" → "Payment #<pay1>" when 18463 is a named id (order numbers are small and
     * never match one). */
    private String hashIds(String text) {
        var matcher = HASH_NUMBER.matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) {
            var name = names.get(Long.parseLong(matcher.group(1)));
            matcher.appendReplacement(out, "#" + (name == null ? matcher.group(1) : name));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** An xlsx export as sheet / cell / type / value lines. The file bytes themselves carry the
     * workbook's creation time, so the cells are what is compared. */
    void xlsx(String file, byte[] bytes) throws IOException {
        var out = new StringBuilder();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (var sheet : workbook) {
                out.append("# ").append(sheet.getSheetName()).append('\n');
                for (var row : sheet) {
                    for (var cell : row) {
                        out.append(cell.getAddress()).append('\t').append(cell.getCellType()).append('\t');
                        switch (cell.getCellType()) {
                            case STRING -> out.append(cell.getStringCellValue());
                            case NUMERIC -> out.append(DateUtil.isCellDateFormatted(cell)
                                    ? cell.getLocalDateTimeCellValue() + " [" + cell.getCellStyle().getDataFormatString() + "]"
                                    : numeric(cell.getNumericCellValue()) + " [" + cell.getCellStyle().getDataFormatString() + "]");
                            case BOOLEAN -> out.append(cell.getBooleanCellValue());
                            case FORMULA -> out.append(cell.getCellFormula());
                            default -> out.append("");
                        }
                        out.append('\n');
                    }
                }
            }
        }
        compare(file, hashIds(out.toString()));
    }

    /** A whole-number cell that is a named id (a shift id in a reading's shift table) becomes its name. */
    private String numeric(double value) {
        if (value == Math.rint(value)) {
            var name = names.get((long) value);
            if (name != null) {
                return name;
            }
        }
        return String.valueOf(value);
    }

    private JsonNode normalise(JsonNode node, String field) {
        if (node instanceof ObjectNode object) {
            // Keys sorted: some response maps are built with Map.of, whose order varies per JVM.
            var copy = MAPPER.createObjectNode();
            var fields = new java.util.TreeMap<String, JsonNode>();
            object.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, value) -> copy.set(key, normalise(value, key)));
            return copy;
        }
        if (node instanceof ArrayNode array) {
            var copy = MAPPER.createArrayNode();
            array.forEach(item -> copy.add(normalise(item, field)));
            return copy;
        }
        if (node.isTextual() && DATABASE_STAMPED.contains(field)) {
            return TextNode.valueOf("<database time>");
        }
        if (node.isIntegralNumber() && field != null && (isIdField(field) || field.equals("value"))) {
            var name = names.get(node.asLong());
            return name == null ? node : TextNode.valueOf(name);
        }
        if (node.isTextual() && node.asText().startsWith("/admin/")) {
            var matcher = PATH_NUMBER.matcher(node.asText());
            var out = new StringBuilder();
            while (matcher.find()) {
                var name = names.get(Long.parseLong(matcher.group(1)));
                matcher.appendReplacement(out, name == null ? matcher.group(1) : name);
            }
            matcher.appendTail(out);
            return TextNode.valueOf(out.toString());
        }
        if (node.isTextual() && node.asText().contains("#")) {
            return TextNode.valueOf(hashIds(node.asText()));
        }
        return node;
    }

    private static boolean isIdField(String field) {
        return field.equals("id") || field.endsWith("Id");
    }

    private static void compare(String file, String actual) throws IOException {
        var path = DIR.resolve(file);
        if (recording()) {
            Files.createDirectories(DIR);
            Files.writeString(path, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(path).as("golden file %s (record it with -Dgolden.record=true on the unchanged code)", path).exists();
        var expected = Files.readString(path, StandardCharsets.UTF_8);
        if (!actual.equals(expected)) {
            // For diffing by hand: target/golden-actual/<file>.
            var out = Path.of("target", "golden-actual");
            Files.createDirectories(out);
            Files.writeString(out.resolve(file), actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).as("golden output %s (actual in target/golden-actual)", file).isEqualTo(expected);
    }

    private Golden() {
    }

    static Golden create() {
        return new Golden();
    }
}
