package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class IngredientService {
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final InventoryMapper inventoryMapper;

    @Transactional(readOnly = true)
    public List<IngredientDto> getAll() {
        return ingredientRepository.findAll().stream().map(inventoryMapper::toDto).toList();
    }

    @Transactional
    public IngredientDto create(CreateIngredientRequest request) {
        var ingredient = new Ingredient();
        ingredient.setName(request.getName());
        ingredient.setUnit(request.getUnit());
        ingredient.setCountSheetCategory(request.getCountSheetCategory());
        ingredient.setItemCode(request.getItemCode());
        ingredientRepository.save(ingredient);
        return inventoryMapper.toDto(ingredient);
    }

    @Transactional
    public IngredientDto update(Long id, UpdateIngredientRequest request) {
        var ingredient = ingredientRepository.findById(id).orElseThrow(IngredientNotFoundException::new);
        ingredient.setName(request.getName());
        ingredient.setUnit(request.getUnit());
        ingredient.setCountSheetCategory(request.getCountSheetCategory());
        ingredient.setActive(request.isActive());
        ingredient.setItemCode(request.getItemCode());
        ingredientRepository.save(ingredient);
        return inventoryMapper.toDto(ingredient);
    }

    // Stage 5.2.1 — every location is shown, including ones with zero movements for this
    // ingredient (e.g. Kitchen before anything has ever been issued/consumed there), rather than
    // only the locations that happen to appear in the ledger.
    @Transactional(readOnly = true)
    public IngredientStockDto getStock(Long id) {
        if (!ingredientRepository.existsById(id)) {
            throw new IngredientNotFoundException();
        }
        BigDecimal totalStock = ingredientStockMovementRepository.sumQuantityByIngredientId(id);
        var lastMovementAt = ingredientStockMovementRepository.findLastMovementAtByIngredientId(id);

        Map<Long, BigDecimal> stockByLocationId = new LinkedHashMap<>();
        for (Object[] row : ingredientStockMovementRepository.sumQuantityByIngredientIdGroupedByLocation(id)) {
            stockByLocationId.put((Long) row[0], (BigDecimal) row[1]);
        }
        var byLocation = locationRepository.findAll().stream()
                .map(location -> new LocationStockDto(location.getId(), location.getName(),
                        stockByLocationId.getOrDefault(location.getId(), BigDecimal.ZERO)))
                .toList();

        return new IngredientStockDto(totalStock, byLocation, lastMovementAt);
    }

    /** Upserts by name (case-insensitive) so re-uploading the same sheet is idempotent. Every
     * row is validated before anything is saved — a bad row fails the whole file rather than
     * leaving a partial import. */
    @Transactional
    public BulkIngredientImportResultDto bulkImport(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BulkImportException(List.of("CSV file is required."));
        }

        List<String> lines = CsvImportUtils.readNonBlankLines(file);
        if (lines.isEmpty()) {
            throw new BulkImportException(List.of("CSV file is empty."));
        }

        String delimiter = CsvImportUtils.detectDelimiter(lines.get(0));
        var columns = parseHeader(lines.get(0), delimiter);

        List<String> errors = new ArrayList<>();
        // LinkedHashMap so a name repeated across rows keeps only its last value, while
        // preserving file order for everything else.
        Map<String, ParsedIngredientRow> rowsByName = new LinkedHashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            List<String> cells = CsvImportUtils.splitCsvLine(lines.get(i), delimiter);
            String name = CsvImportUtils.cellAt(cells, columns.get("NAME"));
            String unitRaw = CsvImportUtils.cellAt(cells, columns.get("UNIT"));
            String categoryRaw = CsvImportUtils.cellAt(cells, columns.get("CATEGORY"));

            if (name == null || name.isBlank()) {
                errors.add("Row " + lineNumber + ": name is required.");
                continue;
            }
            IngredientUnit unit = parseEnum(IngredientUnit.class, unitRaw);
            if (unit == null) {
                errors.add("Row " + lineNumber + ": unit '" + unitRaw + "' must be one of "
                        + List.of(IngredientUnit.values()) + ".");
                continue;
            }
            CountSheetCategory category = parseEnum(CountSheetCategory.class, categoryRaw);
            if (category == null) {
                errors.add("Row " + lineNumber + ": count sheet category '" + categoryRaw + "' must be one of "
                        + List.of(CountSheetCategory.values()) + ".");
                continue;
            }

            rowsByName.put(name.trim().toUpperCase(), new ParsedIngredientRow(name.trim(), unit, category));
        }

        if (!errors.isEmpty()) {
            throw new BulkImportException(errors);
        }

        int created = 0;
        int updated = 0;
        List<Ingredient> saved = new ArrayList<>();
        for (ParsedIngredientRow row : rowsByName.values()) {
            var existing = ingredientRepository.findByNameIgnoreCase(row.name());
            Ingredient ingredient;
            if (existing.isPresent()) {
                ingredient = existing.get();
                updated++;
            } else {
                ingredient = new Ingredient();
                ingredient.setName(row.name());
                ingredient.setActive(true);
                created++;
            }
            ingredient.setUnit(row.unit());
            ingredient.setCountSheetCategory(row.category());
            saved.add(ingredientRepository.save(ingredient));
        }

        return new BulkIngredientImportResultDto(created, updated, saved.stream().map(inventoryMapper::toDto).toList());
    }

    private record ParsedIngredientRow(String name, IngredientUnit unit, CountSheetCategory category) {
    }

    private Map<String, Integer> parseHeader(String headerLine, String delimiter) {
        List<String> cells = CsvImportUtils.splitCsvLine(headerLine, delimiter);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String normalized = CsvImportUtils.normalizeHeaderCell(cells.get(i));
            if (normalized.equals("NAME")) {
                columns.put("NAME", i);
            } else if (normalized.equals("UNIT")) {
                columns.put("UNIT", i);
            } else if (normalized.contains("COUNTSHEET")) {
                columns.put("CATEGORY", i);
            }
        }
        if (!columns.keySet().containsAll(List.of("NAME", "UNIT", "CATEGORY"))) {
            throw new BulkImportException(
                    List.of("CSV header must include NAME, UNIT, and COUNT SHEET columns."));
        }
        return columns;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumClass, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumClass, raw.trim().toUpperCase().replaceAll("[\\s_-]+", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
