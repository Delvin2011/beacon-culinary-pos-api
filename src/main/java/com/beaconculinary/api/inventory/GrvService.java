package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.users.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class GrvService {
    private final GrvRepository grvRepository;
    private final IngredientRepository ingredientRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;

    @Transactional
    public GrvDto create(CreateGrvRequest request) {
        var ingredient = ingredientRepository.findById(request.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        var grv = receiveStock(ingredient, request.getQuantity(), request.getCostPerUnit(),
                request.getSupplierName(), request.getNote(), authService.getCurrentUser());

        return inventoryMapper.toDto(grv);
    }

    @Transactional(readOnly = true)
    public List<GrvDto> list(Long ingredientId, LocalDateTime from, LocalDateTime to) {
        List<Grv> results;
        if (ingredientId != null && from != null && to != null) {
            results = grvRepository.findByIngredientIdAndReceivedAtBetweenOrderByReceivedAtDesc(ingredientId, from, to);
        } else if (ingredientId != null) {
            results = grvRepository.findByIngredientIdOrderByReceivedAtDesc(ingredientId);
        } else if (from != null && to != null) {
            results = grvRepository.findByReceivedAtBetweenOrderByReceivedAtDesc(from, to);
        } else {
            results = grvRepository.findAllByOrderByReceivedAtDesc();
        }
        return results.stream().map(inventoryMapper::toDto).toList();
    }

    /** CSV upload of goods-received records, resolving each row's ingredient by name. Unlike
     * ingredient import, a GRV is a receipt, not master data — every valid row always creates a
     * new GRV, there's no upsert concept. Every row is validated (including that the named
     * ingredient already exists) before anything is saved. */
    @Transactional
    public BulkGrvImportResultDto bulkImport(MultipartFile file) {
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
        List<ParsedGrvRow> rows = new ArrayList<>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            List<String> cells = CsvImportUtils.splitCsvLine(lines.get(i), delimiter);
            String ingredientName = CsvImportUtils.cellAt(cells, columns.get("INGREDIENT"));
            String quantityRaw = CsvImportUtils.cellAt(cells, columns.get("QUANTITY"));
            String costRaw = CsvImportUtils.cellAt(cells, columns.get("COST"));
            String supplierName = CsvImportUtils.cellAt(cells, columns.get("SUPPLIER"));
            String note = CsvImportUtils.cellAt(cells, columns.get("NOTE"));

            if (ingredientName == null || ingredientName.isBlank()) {
                errors.add("Row " + lineNumber + ": ingredient name is required.");
                continue;
            }
            var ingredient = ingredientRepository.findByNameIgnoreCase(ingredientName.trim());
            if (ingredient.isEmpty()) {
                errors.add("Row " + lineNumber + ": ingredient '" + ingredientName
                        + "' does not exist. Import it via /admin/ingredients/bulk-import first.");
                continue;
            }

            BigDecimal quantity = parsePositiveDecimal(quantityRaw);
            if (quantity == null) {
                errors.add("Row " + lineNumber + ": quantity '" + quantityRaw + "' must be a positive number.");
                continue;
            }
            BigDecimal costPerUnit = parseNonNegativeDecimal(costRaw);
            if (costPerUnit == null) {
                errors.add("Row " + lineNumber + ": cost per unit '" + costRaw + "' must be a non-negative number.");
                continue;
            }
            if (supplierName == null || supplierName.isBlank()) {
                errors.add("Row " + lineNumber + ": supplier name is required.");
                continue;
            }

            rows.add(new ParsedGrvRow(ingredient.get(), quantity, costPerUnit, supplierName.trim(),
                    note == null || note.isBlank() ? null : note.trim()));
        }

        if (!errors.isEmpty()) {
            throw new BulkImportException(errors);
        }

        var recordedBy = authService.getCurrentUser();
        List<Grv> saved = new ArrayList<>();
        for (ParsedGrvRow row : rows) {
            saved.add(receiveStock(row.ingredient(), row.quantity(), row.costPerUnit(),
                    row.supplierName(), row.note(), recordedBy));
        }

        return new BulkGrvImportResultDto(saved.size(), saved.stream().map(inventoryMapper::toDto).toList());
    }

    private Grv receiveStock(Ingredient ingredient, BigDecimal quantity, BigDecimal costPerUnit,
                              String supplierName, String note, User recordedBy) {
        var grv = new Grv();
        grv.setIngredient(ingredient);
        grv.setQuantity(quantity);
        grv.setCostPerUnit(costPerUnit);
        grv.setSupplierName(supplierName);
        grv.setNote(note);
        grv.setReceivedBy(recordedBy);
        grvRepository.save(grv);

        var movement = new IngredientStockMovement();
        movement.setIngredient(ingredient);
        movement.setMovementType(MovementType.RECEIVED);
        movement.setQuantity(quantity);
        movement.setCostPerUnit(costPerUnit);
        movement.setSourceType(MovementSourceType.GRV);
        movement.setSourceId(grv.getId());
        movement.setRecordedBy(recordedBy);
        ingredientStockMovementRepository.save(movement);

        return grv;
    }

    private record ParsedGrvRow(Ingredient ingredient, BigDecimal quantity, BigDecimal costPerUnit,
                                 String supplierName, String note) {
    }

    private Map<String, Integer> parseHeader(String headerLine, String delimiter) {
        List<String> cells = CsvImportUtils.splitCsvLine(headerLine, delimiter);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String normalized = CsvImportUtils.normalizeHeaderCell(cells.get(i));
            if (normalized.contains("INGREDIENT")) {
                columns.put("INGREDIENT", i);
            } else if (normalized.equals("QUANTITY")) {
                columns.put("QUANTITY", i);
            } else if (normalized.contains("COST")) {
                columns.put("COST", i);
            } else if (normalized.contains("SUPPLIER")) {
                columns.put("SUPPLIER", i);
            } else if (normalized.contains("NOTE")) {
                columns.put("NOTE", i);
            }
        }
        if (!columns.keySet().containsAll(List.of("INGREDIENT", "QUANTITY", "COST", "SUPPLIER"))) {
            throw new BulkImportException(List.of(
                    "CSV header must include Ingredient Name, Quantity, Cost Per Unit, and Supplier Name columns."));
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
}
