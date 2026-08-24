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
    // Stage 5.2.1/5.2.2: GRV has no location-selection UI yet — every receipt implicitly lands
    // at Main Store until a later stage adds real location awareness here.
    private static final String RECEIVING_LOCATION = "Main Store";

    private final GrvRepository grvRepository;
    private final IngredientRepository ingredientRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;

    @Transactional
    public GrvDto create(CreateGrvRequest request) {
        PurchaseOrder purchaseOrder = null;
        if (request.getPurchaseOrderId() != null) {
            purchaseOrder = purchaseOrderRepository.findById(request.getPurchaseOrderId())
                    .orElseThrow(() -> new InvalidInventoryRequestException("purchaseOrderId does not exist."));
        }

        var grv = new Grv();
        grv.setInvoiceNumber(request.getInvoiceNumber());
        grv.setPurchaseOrder(purchaseOrder);
        grv.setSupplierName(request.getSupplierName());
        grv.setNote(request.getNote());
        grv.setReceivedBy(authService.getCurrentUser());

        for (var lineRequest : request.getLines()) {
            grv.getLines().add(buildLine(grv, lineRequest));
        }

        grvRepository.save(grv);
        writeReceivedMovements(grv);

        return inventoryMapper.toDto(grv);
    }

    private GrvLine buildLine(Grv grv, CreateGrvLineRequest lineRequest) {
        var ingredient = ingredientRepository.findById(lineRequest.getIngredientId())
                .orElseThrow(() -> new InvalidInventoryRequestException("ingredientId does not exist."));

        PurchaseOrderLine purchaseOrderLine = null;
        BigDecimal quantityOrdered = null;
        if (lineRequest.getPurchaseOrderLineId() != null) {
            purchaseOrderLine = purchaseOrderLineRepository.findById(lineRequest.getPurchaseOrderLineId())
                    .orElseThrow(() -> new InvalidInventoryRequestException("purchaseOrderLineId does not exist."));
            if (!purchaseOrderLine.getIngredient().getId().equals(ingredient.getId())) {
                throw new InvalidInventoryRequestException(
                        "ingredientId does not match the linked purchase order line's ingredient.");
            }
            quantityOrdered = purchaseOrderLine.getQuantity();
        }

        var line = new GrvLine();
        line.setGrv(grv);
        line.setIngredient(ingredient);
        line.setPurchaseOrderLine(purchaseOrderLine);
        line.setQuantityOrdered(quantityOrdered);
        line.setQuantityReceived(lineRequest.getQuantityReceived());
        line.setCostPerUnit(lineRequest.getCostPerUnit());
        return line;
    }

    // One movement per line, each pointing at that specific GrvLine's id — never the header's —
    // so a movement always traces back to the exact item that produced it.
    private void writeReceivedMovements(Grv grv) {
        var mainStore = mainStore();
        for (var line : grv.getLines()) {
            var movement = new IngredientStockMovement();
            movement.setIngredient(line.getIngredient());
            movement.setMovementType(MovementType.RECEIVED);
            movement.setQuantity(line.getQuantityReceived());
            movement.setCostPerUnit(line.getCostPerUnit());
            movement.setSourceType(MovementSourceType.GRV);
            movement.setSourceId(line.getId());
            movement.setLocation(mainStore);
            movement.setRecordedBy(grv.getReceivedBy());
            ingredientStockMovementRepository.save(movement);
        }
    }

    @Transactional(readOnly = true)
    public List<GrvDto> list(Long ingredientId, LocalDateTime from, LocalDateTime to, Long purchaseOrderId) {
        return inventoryMapper.toGrvDtoList(grvRepository.search(ingredientId, from, to, purchaseOrderId));
    }

    @Transactional(readOnly = true)
    public GrvDto getById(Long id) {
        var grv = grvRepository.findWithLinesById(id).orElseThrow(GrvNotFoundException::new);
        return inventoryMapper.toDto(grv);
    }

    /** CSV upload — Invoice Number, Ingredient Name, Quantity, Cost Per Unit, Supplier Name, and
     * an optional Note column. Each row still becomes its own one-line, ad-hoc GRV (its own
     * invoice number, no purchase-order link) — the same "every row creates a new GRV" shape
     * bulk import has always had, just carrying the header/line model's now-required invoice
     * number per row rather than one shared across the file. */
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
            String invoiceNumber = CsvImportUtils.cellAt(cells, columns.get("INVOICE"));
            String ingredientName = CsvImportUtils.cellAt(cells, columns.get("INGREDIENT"));
            String quantityRaw = CsvImportUtils.cellAt(cells, columns.get("QUANTITY"));
            String costRaw = CsvImportUtils.cellAt(cells, columns.get("COST"));
            String supplierName = CsvImportUtils.cellAt(cells, columns.get("SUPPLIER"));
            String note = CsvImportUtils.cellAt(cells, columns.get("NOTE"));

            if (invoiceNumber == null || invoiceNumber.isBlank()) {
                errors.add("Row " + lineNumber + ": invoice number is required.");
                continue;
            }
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

            rows.add(new ParsedGrvRow(invoiceNumber.trim(), ingredient.get(), quantity, costPerUnit,
                    supplierName.trim(), note == null || note.isBlank() ? null : note.trim()));
        }

        if (!errors.isEmpty()) {
            throw new BulkImportException(errors);
        }

        var recordedBy = authService.getCurrentUser();
        List<Grv> saved = new ArrayList<>();
        for (ParsedGrvRow row : rows) {
            saved.add(receiveOneLineGrv(row, recordedBy));
        }

        return new BulkGrvImportResultDto(saved.size(), inventoryMapper.toGrvDtoList(saved));
    }

    private Grv receiveOneLineGrv(ParsedGrvRow row, User recordedBy) {
        var grv = new Grv();
        grv.setInvoiceNumber(row.invoiceNumber());
        grv.setSupplierName(row.supplierName());
        grv.setNote(row.note());
        grv.setReceivedBy(recordedBy);

        var line = new GrvLine();
        line.setGrv(grv);
        line.setIngredient(row.ingredient());
        line.setQuantityReceived(row.quantity());
        line.setCostPerUnit(row.costPerUnit());
        grv.getLines().add(line);

        grvRepository.save(grv);
        writeReceivedMovements(grv);

        return grv;
    }

    private Location mainStore() {
        return locationRepository.findByNameIgnoreCase(RECEIVING_LOCATION)
                .orElseThrow(() -> new IllegalStateException(RECEIVING_LOCATION + " location not seeded."));
    }

    private record ParsedGrvRow(String invoiceNumber, Ingredient ingredient, BigDecimal quantity,
                                 BigDecimal costPerUnit, String supplierName, String note) {
    }

    private Map<String, Integer> parseHeader(String headerLine, String delimiter) {
        List<String> cells = CsvImportUtils.splitCsvLine(headerLine, delimiter);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String normalized = CsvImportUtils.normalizeHeaderCell(cells.get(i));
            if (normalized.contains("INVOICE")) {
                columns.put("INVOICE", i);
            } else if (normalized.contains("INGREDIENT")) {
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
        if (!columns.keySet().containsAll(List.of("INVOICE", "INGREDIENT", "QUANTITY", "COST", "SUPPLIER"))) {
            throw new BulkImportException(List.of(
                    "CSV header must include Invoice Number, Ingredient Name, Quantity, Cost Per Unit, and Supplier Name columns."));
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
