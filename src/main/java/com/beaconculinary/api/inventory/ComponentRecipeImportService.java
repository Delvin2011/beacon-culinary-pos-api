package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.ComponentCatalog;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CSV upload that consolidates "create/update a component" and "replace its recipe line items"
 * (previously POST /admin/component-catalog then PUT /admin/components/{id}/recipe) into one
 * sheet: COMPONENT, INGREDIENT (NAME), UNIT, COUNT SHEET, QUANTITIES, BATCH SIZE, and PER PORTION
 * PRICE columns, one row per recipe line, with the owning component's own columns (batch size,
 * price) repeated on every one of its rows. Ingredients and components are both upserted by name
 * (case-insensitive) so the sheet doesn't require either to be created first; each component's
 * recipe is replaced wholesale from its rows, same as the existing PUT endpoint. Every row is
 * validated — including that a component's batch size/price stay consistent across its rows —
 * before anything is saved. */
@Service
@AllArgsConstructor
public class ComponentRecipeImportService {
    private final ComponentCatalogRepository componentCatalogRepository;
    private final RecipeRepository recipeRepository;
    private final IngredientRepository ingredientRepository;
    private final InventoryMapper inventoryMapper;

    @Transactional
    public BulkComponentImportResultDto bulkImport(MultipartFile file) {
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
        // LinkedHashMap so rows are grouped by component in file order, and a component's batch
        // size/price are taken from its first row and cross-checked against the rest.
        Map<String, ParsedComponentGroup> groupsByName = new LinkedHashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            List<String> cells = CsvImportUtils.splitCsvLine(lines.get(i), delimiter);
            String componentName = CsvImportUtils.cellAt(cells, columns.get("COMPONENT"));
            String ingredientName = CsvImportUtils.cellAt(cells, columns.get("INGREDIENT"));
            String unitRaw = CsvImportUtils.cellAt(cells, columns.get("UNIT"));
            String categoryRaw = CsvImportUtils.cellAt(cells, columns.get("CATEGORY"));
            String quantityRaw = CsvImportUtils.cellAt(cells, columns.get("QUANTITY"));
            String batchSizeRaw = CsvImportUtils.cellAt(cells, columns.get("BATCHSIZE"));
            String priceRaw = CsvImportUtils.cellAt(cells, columns.get("PRICE"));

            if (componentName == null || componentName.isBlank()) {
                errors.add("Row " + lineNumber + ": component is required.");
                continue;
            }
            if (ingredientName == null || ingredientName.isBlank()) {
                errors.add("Row " + lineNumber + ": ingredient name is required.");
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
            BigDecimal quantity = parsePositiveDecimal(quantityRaw);
            if (quantity == null) {
                errors.add("Row " + lineNumber + ": quantity '" + quantityRaw + "' must be a positive number.");
                continue;
            }
            Integer batchSize = parsePositiveInt(batchSizeRaw);
            if (batchSize == null) {
                errors.add("Row " + lineNumber + ": batch size '" + batchSizeRaw + "' must be a positive whole number.");
                continue;
            }
            BigDecimal price = parseNonNegativeDecimal(priceRaw);
            if (price == null) {
                errors.add("Row " + lineNumber + ": per portion price '" + priceRaw + "' must be a non-negative number.");
                continue;
            }

            var group = groupsByName.computeIfAbsent(componentName.trim().toUpperCase(),
                    k -> new ParsedComponentGroup(componentName.trim()));

            if (group.batchSize == null) {
                group.batchSize = batchSize;
            } else if (!group.batchSize.equals(batchSize)) {
                errors.add("Row " + lineNumber + ": batch size " + batchSize + " conflicts with batch size "
                        + group.batchSize + " already used for component '" + group.componentName
                        + "' earlier in the file.");
                continue;
            }
            if (group.price == null) {
                group.price = price;
            } else if (group.price.compareTo(price) != 0) {
                errors.add("Row " + lineNumber + ": per portion price " + price + " conflicts with price "
                        + group.price + " already used for component '" + group.componentName
                        + "' earlier in the file.");
                continue;
            }

            // Keyed by ingredient name so a repeated ingredient within the same component keeps
            // only its last row, same as the ingredient bulk-import's dedupe-by-name behavior.
            group.lines.put(ingredientName.trim().toUpperCase(),
                    new ParsedLine(ingredientName.trim(), unit, category, quantity));
        }

        if (!errors.isEmpty()) {
            throw new BulkImportException(errors);
        }
        if (groupsByName.isEmpty()) {
            throw new BulkImportException(List.of("CSV file has no data rows."));
        }

        int componentsCreated = 0;
        int componentsUpdated = 0;
        int ingredientsCreated = 0;
        int ingredientsUpdated = 0;
        List<ComponentRecipeDto> results = new ArrayList<>();

        for (var group : groupsByName.values()) {
            var existingComponent = componentCatalogRepository.findByNameIgnoreCase(group.componentName);
            ComponentCatalog componentCatalog;
            if (existingComponent.isPresent()) {
                componentCatalog = existingComponent.get();
                componentsUpdated++;
            } else {
                componentCatalog = new ComponentCatalog();
                componentCatalog.setName(group.componentName);
                componentCatalog.setActive(true);
                componentsCreated++;
            }
            componentCatalog.setExtraPrice(group.price);
            componentCatalogRepository.save(componentCatalog);

            var recipe = recipeRepository.findWithLinesByComponentCatalogId(componentCatalog.getId()).orElseGet(() -> {
                var created = new Recipe();
                created.setComponentCatalog(componentCatalog);
                return created;
            });
            recipe.setBatchSize(group.batchSize);
            recipe.getLines().clear();

            for (var parsedLine : group.lines.values()) {
                var existingIngredient = ingredientRepository.findByNameIgnoreCase(parsedLine.name());
                Ingredient ingredient;
                if (existingIngredient.isPresent()) {
                    ingredient = existingIngredient.get();
                    ingredientsUpdated++;
                } else {
                    ingredient = new Ingredient();
                    ingredient.setName(parsedLine.name());
                    ingredient.setActive(true);
                    ingredientsCreated++;
                }
                ingredient.setUnit(parsedLine.unit());
                ingredient.setCountSheetCategory(parsedLine.category());
                ingredientRepository.save(ingredient);

                var recipeLine = new RecipeLine();
                recipeLine.setRecipe(recipe);
                recipeLine.setIngredient(ingredient);
                recipeLine.setQuantity(parsedLine.quantity());
                recipe.getLines().add(recipeLine);
            }

            recipeRepository.save(recipe);

            var dto = new ComponentRecipeDto();
            dto.setComponentCatalogId(componentCatalog.getId());
            dto.setComponentName(componentCatalog.getName());
            dto.setExtraPrice(componentCatalog.getExtraPrice());
            dto.setBatchSize(recipe.getBatchSize());
            dto.setLines(recipe.getLines().stream().map(inventoryMapper::toDto).toList());
            results.add(dto);
        }

        return new BulkComponentImportResultDto(componentsCreated, componentsUpdated, ingredientsCreated,
                ingredientsUpdated, results);
    }

    private static class ParsedComponentGroup {
        private final String componentName;
        private Integer batchSize;
        private BigDecimal price;
        private final Map<String, ParsedLine> lines = new LinkedHashMap<>();

        private ParsedComponentGroup(String componentName) {
            this.componentName = componentName;
        }
    }

    private record ParsedLine(String name, IngredientUnit unit, CountSheetCategory category, BigDecimal quantity) {
    }

    private Map<String, Integer> parseHeader(String headerLine, String delimiter) {
        List<String> cells = CsvImportUtils.splitCsvLine(headerLine, delimiter);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String normalized = CsvImportUtils.normalizeHeaderCell(cells.get(i));
            if (normalized.equals("COMPONENT")) {
                columns.put("COMPONENT", i);
            } else if (normalized.contains("INGREDIENT")) {
                columns.put("INGREDIENT", i);
            } else if (normalized.equals("UNIT")) {
                columns.put("UNIT", i);
            } else if (normalized.contains("COUNTSHEET")) {
                columns.put("CATEGORY", i);
            } else if (normalized.contains("QUANTIT")) {
                columns.put("QUANTITY", i);
            } else if (normalized.contains("BATCHSIZE")) {
                columns.put("BATCHSIZE", i);
            } else if (normalized.contains("PRICE")) {
                columns.put("PRICE", i);
            }
        }
        if (!columns.keySet().containsAll(
                List.of("COMPONENT", "INGREDIENT", "UNIT", "CATEGORY", "QUANTITY", "BATCHSIZE", "PRICE"))) {
            throw new BulkImportException(List.of("CSV header must include Component, Ingredient (Name), Unit, "
                    + "Count Sheet, Quantities, Batch Size, and Per Portion Price columns."));
        }
        return columns;
    }

    private static BigDecimal parsePositiveDecimal(String raw) {
        var value = parseDecimal(raw);
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }

    private static BigDecimal parseNonNegativeDecimal(String raw) {
        var value = parseDecimal(raw);
        return value != null && value.compareTo(BigDecimal.ZERO) >= 0 ? value : null;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
