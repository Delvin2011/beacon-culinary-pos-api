package com.beaconculinary.api.support.baseline;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * T0-7: the schema of one SQL Server database (dbo), read from the system catalogue. Used to generate a
 * baseline migration ({@link BaselineScriptGenerator}) and to compare a baseline-built database with a fully
 * migrated one ({@link #describe()}).
 *
 * <p>Covers what the migrations use: tables and columns (type, nullability, identity, computed, default),
 * primary keys and unique constraints, check constraints, foreign keys (with cascade actions), indexes (key
 * and included columns, direction, filter), views and triggers. Names SQL Server generated itself
 * ({@code is_system_named}) are kept apart: they differ between databases, so comparisons ignore them.
 */
public final class SchemaCatalog {
    /** Tables that belong to tooling, not to the application. */
    public static final List<String> IGNORED_TABLES = List.of("flyway_schema_history");

    public record Column(int ordinal, String name, String type, boolean nullable, Long identitySeed, Long identityIncrement,
                         String computed, boolean persisted, Constraint defaultConstraint) {
    }

    /** A named constraint's name and whether SQL Server generated it. */
    public record Constraint(String name, boolean systemNamed, String definition) {
    }

    public record IndexColumn(String name, boolean descending) {
        String sql() {
            return "[" + name + "]" + (descending ? " DESC" : "");
        }
    }

    public record Key(String table, String name, boolean systemNamed, boolean primary, boolean clustered,
                      List<IndexColumn> columns) {
    }

    public record Check(String table, String name, boolean systemNamed, String column, String definition) {
    }

    public record ForeignKey(String table, String name, boolean systemNamed, List<String> columns, String referencedTable,
                             List<String> referencedColumns, String onDelete, String onUpdate) {
    }

    public record Index(String table, String name, boolean unique, boolean clustered, List<IndexColumn> keyColumns,
                        List<String> includedColumns, String filter) {
    }

    public record Module(String name, String table, String definition) {
    }

    public record Table(String name, List<Column> columns) {
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final List<Key> keys = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final List<ForeignKey> foreignKeys = new ArrayList<>();
    private final List<Index> indexes = new ArrayList<>();
    private final List<Module> views = new ArrayList<>();
    private final List<Module> triggers = new ArrayList<>();
    private final Map<String, List<String>> viewDependencies = new LinkedHashMap<>();
    /** System-generated constraint name to its deterministic name (see {@link #name}). */
    private final Map<String, String> deterministicNames = new LinkedHashMap<>();

    private SchemaCatalog() {
    }

    public Map<String, Table> tables() {
        return tables;
    }

    public List<Key> keys() {
        return keys;
    }

    public List<Check> checks() {
        return checks;
    }

    public List<ForeignKey> foreignKeys() {
        return foreignKeys;
    }

    public List<Index> indexes() {
        return indexes;
    }

    /** Views in an order that creates each after the views it reads. */
    public List<Module> viewsInDependencyOrder() {
        var byName = views.stream().collect(Collectors.toMap(Module::name, v -> v, (a, b) -> a, LinkedHashMap::new));
        var ordered = new ArrayList<Module>();
        var done = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        while (ordered.size() < views.size()) {
            var progressed = false;
            for (var view : byName.values()) {
                if (done.contains(view.name())) {
                    continue;
                }
                var deps = viewDependencies.getOrDefault(view.name(), List.of()).stream()
                        .filter(byName::containsKey).toList();
                if (done.containsAll(deps)) {
                    ordered.add(view);
                    done.add(view.name());
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException("Circular view dependencies: " + byName.keySet());
            }
        }
        return ordered;
    }

    public List<Module> triggers() {
        return triggers;
    }

    // ---- reading --------------------------------------------------------------------------------

    public static SchemaCatalog read(Connection connection) throws SQLException {
        var catalog = new SchemaCatalog();
        catalog.readTables(connection);
        catalog.readKeys(connection);
        catalog.readChecks(connection);
        catalog.readForeignKeys(connection);
        catalog.readIndexes(connection);
        catalog.readModules(connection);
        catalog.assignDeterministicNames();
        return catalog;
    }

    private static final String USER_TABLE = "SCHEMA_NAME(t.schema_id) = 'dbo' AND t.is_ms_shipped = 0 AND t.name NOT IN ('flyway_schema_history')";

    private void readTables(Connection connection) throws SQLException {
        var sql = """
                SELECT t.name AS tbl, c.column_id, c.name AS col, ty.name AS type_name, c.max_length, c.precision, c.scale,
                       c.is_nullable, ic.seed_value, ic.increment_value, cc.definition AS computed, cc.is_persisted,
                       dc.name AS default_name, dc.is_system_named AS default_system_named, dc.definition AS default_definition
                FROM sys.tables t
                JOIN sys.columns c ON c.object_id = t.object_id
                JOIN sys.types ty ON ty.user_type_id = c.user_type_id
                LEFT JOIN sys.identity_columns ic ON ic.object_id = c.object_id AND ic.column_id = c.column_id
                LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
                LEFT JOIN sys.default_constraints dc ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
                WHERE %s
                ORDER BY t.name, c.column_id""".formatted(USER_TABLE);
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            var columns = new LinkedHashMap<String, List<Column>>();
            while (rs.next()) {
                // The position in the table, not column_id: dropped columns leave gaps in column_id.
                var position = columns.getOrDefault(rs.getString("tbl"), List.of()).size() + 1;
                var defaultName = rs.getString("default_name");
                var dflt = defaultName == null ? null
                        : new Constraint(defaultName, rs.getBoolean("default_system_named"), rs.getString("default_definition"));
                var computed = rs.getString("computed");
                columns.computeIfAbsent(rs.getString("tbl"), k -> new ArrayList<>()).add(new Column(position,
                        rs.getString("col"), typeSql(rs), rs.getBoolean("is_nullable"), longOrNull(rs, "seed_value"),
                        longOrNull(rs, "increment_value"), computed, computed != null && rs.getBoolean("is_persisted"), dflt));
            }
            columns.forEach((table, cols) -> tables.put(table, new Table(table, List.copyOf(cols))));
        }
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    /** The column type as written in DDL. */
    static String typeSql(ResultSet rs) throws SQLException {
        var name = rs.getString("type_name").toLowerCase(Locale.ROOT);
        int maxLength = rs.getInt("max_length");
        int precision = rs.getInt("precision");
        int scale = rs.getInt("scale");
        return switch (name) {
            case "nvarchar", "nchar" -> name + "(" + (maxLength == -1 ? "max" : String.valueOf(maxLength / 2)) + ")";
            case "varchar", "char", "varbinary", "binary" -> name + "(" + (maxLength == -1 ? "max" : String.valueOf(maxLength)) + ")";
            case "decimal", "numeric" -> name + "(" + precision + ", " + scale + ")";
            case "datetime2", "time", "datetimeoffset" -> name + "(" + scale + ")";
            default -> name;
        };
    }

    private void readKeys(Connection connection) throws SQLException {
        var sql = """
                SELECT t.name AS tbl, kc.name, kc.is_system_named, kc.type, i.type AS index_type, ic.key_ordinal,
                       col.name AS col, ic.is_descending_key
                FROM sys.key_constraints kc
                JOIN sys.tables t ON t.object_id = kc.parent_object_id
                JOIN sys.indexes i ON i.object_id = kc.parent_object_id AND i.index_id = kc.unique_index_id
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id AND ic.is_included_column = 0
                JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
                WHERE %s
                ORDER BY t.name, kc.name, ic.key_ordinal""".formatted(USER_TABLE);
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            var grouped = new LinkedHashMap<String, List<Object[]>>();
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getString("tbl") + "\u0000" + rs.getString("name"), k -> new ArrayList<>())
                        .add(new Object[]{rs.getString("tbl"), rs.getString("name"), rs.getBoolean("is_system_named"),
                                "PK".equals(rs.getString("type").trim()), rs.getInt("index_type") == 1,
                                new IndexColumn(rs.getString("col"), rs.getBoolean("is_descending_key"))});
            }
            for (var rows : grouped.values()) {
                var first = rows.get(0);
                keys.add(new Key((String) first[0], (String) first[1], (Boolean) first[2], (Boolean) first[3], (Boolean) first[4],
                        rows.stream().map(r -> (IndexColumn) r[5]).toList()));
            }
        }
    }

    private void readChecks(Connection connection) throws SQLException {
        var sql = """
                SELECT t.name AS tbl, cc.name, cc.is_system_named, col.name AS col, cc.definition
                FROM sys.check_constraints cc
                JOIN sys.tables t ON t.object_id = cc.parent_object_id
                LEFT JOIN sys.columns col ON col.object_id = cc.parent_object_id AND col.column_id = cc.parent_column_id
                WHERE %s
                ORDER BY t.name, cc.name""".formatted(USER_TABLE);
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                checks.add(new Check(rs.getString("tbl"), rs.getString("name"), rs.getBoolean("is_system_named"),
                        rs.getString("col"), rs.getString("definition")));
            }
        }
    }

    private void readForeignKeys(Connection connection) throws SQLException {
        var sql = """
                SELECT t.name AS tbl, fk.name, fk.is_system_named, pc.name AS col, rt.name AS ref_tbl, rc.name AS ref_col,
                       fk.delete_referential_action_desc, fk.update_referential_action_desc, fkc.constraint_column_id
                FROM sys.foreign_keys fk
                JOIN sys.tables t ON t.object_id = fk.parent_object_id
                JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
                JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
                JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
                WHERE %s
                ORDER BY t.name, fk.name, fkc.constraint_column_id""".formatted(USER_TABLE);
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            var grouped = new LinkedHashMap<String, List<String[]>>();
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getString("tbl") + "\u0000" + rs.getString("name"), k -> new ArrayList<>())
                        .add(new String[]{rs.getString("tbl"), rs.getString("name"), String.valueOf(rs.getBoolean("is_system_named")),
                                rs.getString("col"), rs.getString("ref_tbl"), rs.getString("ref_col"),
                                rs.getString("delete_referential_action_desc"), rs.getString("update_referential_action_desc")});
            }
            for (var rows : grouped.values()) {
                var first = rows.get(0);
                foreignKeys.add(new ForeignKey(first[0], first[1], Boolean.parseBoolean(first[2]),
                        rows.stream().map(r -> r[3]).toList(), first[4], rows.stream().map(r -> r[5]).toList(), first[6], first[7]));
            }
        }
    }

    private void readIndexes(Connection connection) throws SQLException {
        var sql = """
                SELECT t.name AS tbl, i.name, i.is_unique, i.type, i.filter_definition, col.name AS col,
                       ic.is_descending_key, ic.is_included_column, ic.key_ordinal, ic.index_column_id
                FROM sys.indexes i
                JOIN sys.tables t ON t.object_id = i.object_id
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns col ON col.object_id = ic.object_id AND col.column_id = ic.column_id
                WHERE %s AND i.is_primary_key = 0 AND i.is_unique_constraint = 0 AND i.type IN (1, 2)
                ORDER BY t.name, i.name, ic.is_included_column, ic.key_ordinal, ic.index_column_id""".formatted(USER_TABLE);
        try (var st = connection.createStatement(); var rs = st.executeQuery(sql)) {
            record Row(String table, String name, boolean unique, boolean clustered, String filter, String col, boolean desc,
                       boolean included) {
            }
            var grouped = new LinkedHashMap<String, List<Row>>();
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getString("tbl") + "\u0000" + rs.getString("name"), k -> new ArrayList<>())
                        .add(new Row(rs.getString("tbl"), rs.getString("name"), rs.getBoolean("is_unique"), rs.getInt("type") == 1,
                                rs.getString("filter_definition"), rs.getString("col"), rs.getBoolean("is_descending_key"),
                                rs.getBoolean("is_included_column")));
            }
            for (var rows : grouped.values()) {
                var first = rows.get(0);
                indexes.add(new Index(first.table(), first.name(), first.unique(), first.clustered(),
                        rows.stream().filter(r -> !r.included()).map(r -> new IndexColumn(r.col(), r.desc())).toList(),
                        rows.stream().filter(Row::included).map(Row::col).toList(), first.filter()));
            }
        }
    }

    private void readModules(Connection connection) throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery("""
                SELECT v.name, m.definition FROM sys.views v JOIN sys.sql_modules m ON m.object_id = v.object_id
                WHERE SCHEMA_NAME(v.schema_id) = 'dbo' AND v.is_ms_shipped = 0 ORDER BY v.name""")) {
            while (rs.next()) {
                views.add(new Module(rs.getString("name"), null, rs.getString("definition")));
            }
        }
        try (var st = connection.createStatement(); var rs = st.executeQuery("""
                SELECT DISTINCT v.name AS viewName, rv.name AS dependsOn
                FROM sys.sql_expression_dependencies d
                JOIN sys.views v ON v.object_id = d.referencing_id
                JOIN sys.views rv ON rv.object_id = d.referenced_id""")) {
            while (rs.next()) {
                viewDependencies.computeIfAbsent(rs.getString("viewName"), k -> new ArrayList<>()).add(rs.getString("dependsOn"));
            }
        }
        try (var st = connection.createStatement(); var rs = st.executeQuery("""
                SELECT tr.name, OBJECT_NAME(tr.parent_id) AS tbl, m.definition
                FROM sys.triggers tr JOIN sys.sql_modules m ON m.object_id = tr.object_id
                WHERE tr.parent_class = 1 AND tr.is_ms_shipped = 0 ORDER BY tr.name""")) {
            while (rs.next()) {
                triggers.add(new Module(rs.getString("name"), rs.getString("tbl"), rs.getString("definition")));
            }
        }
    }

    // ---- names ----------------------------------------------------------------------------------

    /**
     * The name a constraint gets in a baseline: its own name, or for one SQL Server named itself a
     * deterministic one (DF_table_column, PK_table, UQ_table_columns, CK_table_column, FK_table_column_reftable),
     * made unique against every other constraint and index name.
     */
    public String name(String original) {
        return deterministicNames.getOrDefault(original, original);
    }

    private void assignDeterministicNames() {
        var taken = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        tables.values().forEach(t -> t.columns().stream().map(Column::defaultConstraint)
                .filter(d -> d != null && !d.systemNamed()).forEach(d -> taken.add(d.name())));
        keys.stream().filter(k -> !k.systemNamed()).forEach(k -> taken.add(k.name()));
        checks.stream().filter(c -> !c.systemNamed()).forEach(c -> taken.add(c.name()));
        foreignKeys.stream().filter(f -> !f.systemNamed()).forEach(f -> taken.add(f.name()));
        indexes.forEach(i -> taken.add(i.name()));

        record Wanted(String original, String candidate, String order) {
        }
        var wanted = new ArrayList<Wanted>();
        for (var t : tables.values()) {
            for (var c : t.columns()) {
                var d = c.defaultConstraint();
                if (d != null && d.systemNamed()) {
                    wanted.add(new Wanted(d.name(), "DF_" + t.name() + "_" + c.name(), "1" + t.name() + c.name()));
                }
            }
        }
        for (var k : keys) {
            if (k.systemNamed()) {
                var candidate = k.primary() ? "PK_" + k.table()
                        : "UQ_" + k.table() + "_" + k.columns().stream().map(IndexColumn::name).collect(Collectors.joining("_"));
                wanted.add(new Wanted(k.name(), candidate, "2" + candidate));
            }
        }
        for (var c : checks) {
            if (c.systemNamed()) {
                var candidate = "CK_" + c.table() + (c.column() == null ? "" : "_" + c.column());
                wanted.add(new Wanted(c.name(), candidate, "3" + candidate + c.definition()));
            }
        }
        for (var f : foreignKeys) {
            if (f.systemNamed()) {
                var candidate = "FK_" + f.table() + "_" + String.join("_", f.columns()) + "_" + f.referencedTable();
                wanted.add(new Wanted(f.name(), candidate, "4" + candidate));
            }
        }
        wanted.sort(Comparator.comparing(Wanted::order));
        for (var w : wanted) {
            var base = w.candidate().length() > 120 ? w.candidate().substring(0, 120) : w.candidate();
            var name = base;
            for (int n = 2; taken.contains(name); n++) {
                name = base + "_" + n;
            }
            taken.add(name);
            deterministicNames.put(w.original(), name);
        }
    }

    // ---- comparison -----------------------------------------------------------------------------

    /**
     * One normalised line per schema element, sorted, for comparing two databases. Names SQL Server generated
     * are replaced by their deterministic names ({@link #name}), the names a baseline gives them; everything
     * else, definitions included, must match.
     * Module definitions are compared with whitespace collapsed and case folded.
     */
    public List<String> describe() {
        var lines = new ArrayList<String>();
        for (var table : tables.values()) {
            lines.add("table " + table.name());
            for (var c : table.columns()) {
                lines.add("column " + table.name() + "." + c.name() + " #" + c.ordinal() + " " + c.type()
                        + (c.nullable() ? " NULL" : " NOT NULL")
                        + (c.identitySeed() == null ? "" : " IDENTITY(" + c.identitySeed() + "," + c.identityIncrement() + ")")
                        + (c.computed() == null ? "" : " AS " + c.computed() + (c.persisted() ? " PERSISTED" : ""))
                        + (c.defaultConstraint() == null ? "" : " DEFAULT " + c.defaultConstraint().definition()
                                + " [" + name(c.defaultConstraint().name()) + "]"));
            }
        }
        for (var k : keys) {
            lines.add((k.primary() ? "primary key " : "unique ") + k.table() + " [" + name(k.name()) + "]"
                    + (k.clustered() ? " CLUSTERED " : " NONCLUSTERED ") + cols(k.columns()));
        }
        for (var c : checks) {
            lines.add("check " + c.table() + (c.column() == null ? "" : "." + c.column()) + " [" + name(c.name()) + "]"
                    + " " + c.definition());
        }
        for (var f : foreignKeys) {
            lines.add("foreign key " + f.table() + " [" + name(f.name()) + "]" + " " + f.columns() + " -> "
                    + f.referencedTable() + f.referencedColumns() + " ON DELETE " + f.onDelete() + " ON UPDATE " + f.onUpdate());
        }
        for (var i : indexes) {
            lines.add("index " + i.table() + " [" + i.name() + "]" + (i.unique() ? " UNIQUE" : "")
                    + (i.clustered() ? " CLUSTERED " : " NONCLUSTERED ") + cols(i.keyColumns())
                    + (i.includedColumns().isEmpty() ? "" : " INCLUDE " + i.includedColumns())
                    + (i.filter() == null ? "" : " WHERE " + i.filter()));
        }
        for (var v : views) {
            lines.add("view " + v.name() + " " + normalise(v.definition()));
        }
        for (var t : triggers) {
            lines.add("trigger " + t.name() + " on " + t.table() + " " + normalise(t.definition()));
        }
        lines.sort(Comparator.naturalOrder());
        return lines;
    }

    private static String cols(List<IndexColumn> columns) {
        return columns.stream().map(IndexColumn::sql).collect(Collectors.joining(", ", "(", ")"));
    }

    /** Collapses whitespace and folds case, so formatting differences don't count. */
    static String normalise(String definition) {
        return definition == null ? "" : definition.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
