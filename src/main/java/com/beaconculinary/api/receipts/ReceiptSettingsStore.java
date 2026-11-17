package com.beaconculinary.api.receipts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * O2b: SQL for the single receipt_settings row (V108, id = 1). updated_at is a UTC LocalDateTime.
 * Runs in the caller's transaction.
 */
@Repository
public class ReceiptSettingsStore {
    private static final int ROW_ID = 1;

    private final JdbcTemplate jdbc;

    public ReceiptSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored values; addressLines holds only the non-null lines, in order. */
    public record Row(String businessName, List<String> addressLines, String phone, String vatNumber,
                      String footerText, boolean showVatLine, LocalDateTime updatedAt, String updatedByName) {
    }

    public Optional<Row> find() {
        return jdbc.query("""
                SELECT [business_name], [address_line_1], [address_line_2], [address_line_3], [address_line_4],
                       [phone], [vat_number], [footer_text], [show_vat_line], [updated_at], [updated_by_name]
                FROM [receipt_settings] WHERE [id] = ?""", (rs, i) -> {
            var lines = new ArrayList<String>();
            for (int n = 1; n <= ReceiptSettingsService.MAX_ADDRESS_LINES; n++) {
                var line = rs.getString("address_line_" + n);
                if (line != null) {
                    lines.add(line);
                }
            }
            return new Row(rs.getString("business_name"), List.copyOf(lines), rs.getString("phone"),
                    rs.getString("vat_number"), rs.getString("footer_text"), rs.getBoolean("show_vat_line"),
                    rs.getObject("updated_at", LocalDateTime.class), rs.getString("updated_by_name"));
        }, ROW_ID).stream().findFirst();
    }

    /**
     * Replaces every value and records who saved, at the database's UTC time. Inserts row 1 if it's
     * missing (V108 creates it, so that's only a safeguard).
     */
    public void save(String businessName, List<String> addressLines, String phone, String vatNumber,
                     String footerText, boolean showVatLine, long updatedById, String updatedByName) {
        var line = new String[ReceiptSettingsService.MAX_ADDRESS_LINES];
        for (int n = 0; n < addressLines.size(); n++) {
            line[n] = addressLines.get(n);
        }
        int updated = jdbc.update("""
                UPDATE [receipt_settings]
                SET [business_name] = ?, [address_line_1] = ?, [address_line_2] = ?, [address_line_3] = ?,
                    [address_line_4] = ?, [phone] = ?, [vat_number] = ?, [footer_text] = ?, [show_vat_line] = ?,
                    [updated_at] = SYSUTCDATETIME(), [updated_by] = ?, [updated_by_name] = ?
                WHERE [id] = ?""",
                businessName, line[0], line[1], line[2], line[3], phone, vatNumber, footerText, showVatLine,
                updatedById, updatedByName, ROW_ID);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO [receipt_settings] ([id], [business_name], [address_line_1], [address_line_2],
                        [address_line_3], [address_line_4], [phone], [vat_number], [footer_text], [show_vat_line],
                        [updated_at], [updated_by], [updated_by_name])
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSUTCDATETIME(), ?, ?)""",
                    ROW_ID, businessName, line[0], line[1], line[2], line[3], phone, vatNumber, footerText,
                    showVatLine, updatedById, updatedByName);
        }
    }
}
