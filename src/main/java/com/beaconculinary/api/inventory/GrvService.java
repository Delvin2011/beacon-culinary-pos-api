package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.users.User;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class GrvService {
    // Stage 5.2.1/5.2.2: GRV has no location-selection UI yet — every receipt implicitly lands
    // at Main Store until a later stage adds real location awareness here.
    private static final String RECEIVING_LOCATION = "Main Store";
    private static final String COST_PER_UNIT_RETIRED =
            "costPerUnit is no longer accepted: send unitPrice (as written on the invoice) and pricesIncludeVat.";

    private final GrvRepository grvRepository;
    private final IngredientRepository ingredientRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final IngredientStockMovementRepository ingredientStockMovementRepository;
    private final LocationRepository locationRepository;
    private final AuthService authService;
    private final InventoryMapper inventoryMapper;
    private final VatSettings vatSettings;
    private final Clock clock;

    @Transactional
    public GrvDto create(CreateGrvRequest request) {
        if (request.getLines().stream().anyMatch(line -> line.getCostPerUnit() != null)) {
            throw new InvalidInventoryRequestException(COST_PER_UNIT_RETIRED);
        }

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
        grv.setPricesIncludeVat(request.getPricesIncludeVat());
        grv.setInvoiceTotalInclVat(request.getInvoiceTotalInclVat());
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
        var vatCode = lineRequest.getVatCode() != null ? lineRequest.getVatCode() : ingredient.getDefaultVatCode();
        applyVat(line, grv.getPricesIncludeVat(), lineRequest.getUnitPrice(), vatCode);
        return line;
    }

    /**
     * GRV VAT handling §2: snapshots the VAT code and its current rate, derives both bases from
     * the price as invoiced, and sets the ledger cost to the VAT-inclusive unit cost. Used for new
     * lines and for every same-day edit (which recomputes everything, legacy lines included).
     */
    private void applyVat(GrvLine line, boolean pricesIncludeVat, BigDecimal unitPrice, VatCode vatCode) {
        var rate = vatSettings.rateFor(vatCode);
        var amounts = GrvVatCalculator.compute(line.getQuantityReceived(), unitPrice, pricesIncludeVat, rate);

        line.setUnitPriceAsInvoiced(unitPrice);
        line.setVatCode(vatCode);
        line.setVatRate(rate);
        line.setUnitCostExclVat(amounts.unitCostExclVat());
        line.setUnitCostInclVat(amounts.unitCostInclVat());
        line.setLineExclVat(amounts.lineExclVat());
        line.setLineVat(amounts.lineVat());
        line.setLineInclVat(amounts.lineInclVat());
        line.setCostPerUnit(amounts.unitCostInclVat());
    }

    // One movement per line, each pointing at that specific GrvLine's id — never the header's —
    // so a movement always traces back to the exact item that produced it. The movement's cost is
    // the ledger cost (VAT-inclusive unit cost).
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

    /** Stage 5.2.6 — a same-day correction of a data-entry mistake (invoice number, supplier,
     * note, per-line quantity/price). Ingredient, purchase-order link, and line identity are never
     * touched here — {@code lines} must name exactly the GRV's existing line ids, and a line
     * pointed at the wrong ingredient has to be deleted/re-entered, not repointed. Each edited
     * line's RECEIVED movement is adjusted in place in the same transaction, so stock levels and
     * cost figures never drift from what the corrected GRV displays; receiptVariance needs no
     * separate recompute since it's derived at read time from the (now-updated)
     * quantityReceived.
     *
     * <p>GRV VAT handling §5: the inclusive/exclusive toggle, the printed total, and each line's
     * price, quantity and VAT code can be corrected; every derived figure is recomputed. A line's
     * VAT code falls back to its current code, then to the ingredient default (a legacy line has
     * none of its own). */
    @Transactional
    public GrvDto update(Long id, UpdateGrvRequest request) {
        if (request.getLines().stream().anyMatch(line -> line.getCostPerUnit() != null)) {
            throw new InvalidInventoryRequestException(COST_PER_UNIT_RETIRED);
        }

        var grv = grvRepository.findWithLinesById(id).orElseThrow(GrvNotFoundException::new);

        // grv.receivedAt is stored in UTC (SYSUTCDATETIME() at insert) — comparing its raw date
        // against LocalDate.now() would silently use the wrong calendar day for any receipt near
        // midnight in the business's actual timezone, so both sides are resolved through the same
        // business-zone clock before comparing.
        var receivedDate = grv.getReceivedAt().atZone(ZoneOffset.UTC)
                .withZoneSameInstant(clock.getZone())
                .toLocalDate();
        if (!receivedDate.equals(LocalDate.now(clock))) {
            throw new GrvEditWindowClosedException();
        }

        var existingLinesById = grv.getLines().stream().collect(Collectors.toMap(GrvLine::getId, l -> l));
        var requestedIds = request.getLines().stream().map(UpdateGrvLineRequest::getId).collect(Collectors.toSet());
        if (!requestedIds.equals(existingLinesById.keySet())) {
            throw new InvalidInventoryRequestException(
                    "lines must include exactly this GRV's existing line ids — lines cannot be added or removed via this endpoint.");
        }

        grv.setInvoiceNumber(request.getInvoiceNumber());
        grv.setSupplierName(request.getSupplierName());
        grv.setNote(request.getNote());
        grv.setPricesIncludeVat(request.getPricesIncludeVat());
        grv.setInvoiceTotalInclVat(request.getInvoiceTotalInclVat());

        for (var lineRequest : request.getLines()) {
            var line = existingLinesById.get(lineRequest.getId());
            line.setQuantityReceived(lineRequest.getQuantityReceived());
            var vatCode = lineRequest.getVatCode() != null ? lineRequest.getVatCode()
                    : line.getVatCode() != null ? line.getVatCode()
                    : line.getIngredient().getDefaultVatCode();
            applyVat(line, request.getPricesIncludeVat(), lineRequest.getUnitPrice(), vatCode);

            var movement = ingredientStockMovementRepository
                    .findBySourceTypeAndSourceId(MovementSourceType.GRV, line.getId())
                    .orElseThrow(() -> new IllegalStateException("No stock movement found for GRV line " + line.getId() + "."));
            movement.setQuantity(line.getQuantityReceived());
            movement.setCostPerUnit(line.getCostPerUnit());
            ingredientStockMovementRepository.save(movement);
        }

        grv.setEditedBy(authService.getCurrentUser());
        grv.setEditedAt(LocalDateTime.now(clock));

        grvRepository.save(grv);
        return inventoryMapper.toDto(grv);
    }

    /**
     * CSV upload. Columns: Invoice Number, Ingredient Name, Quantity, Unit Price, Prices Include
     * VAT (Y/N), Supplier Name; optional VAT Code, Invoice Total and Note.
     *
     * <p>GRV VAT handling §6: rows sharing an invoice number become one multi-line GRV, so its
     * invoice check compares the printed total with the whole invoice. Rows of one invoice must
     * agree on supplier, Prices Include VAT and Invoice Total. Every row is validated before
     * anything is saved — any error fails the whole file.
     */
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
        // Invoice number -> its rows, in file order.
        Map<String, List<ParsedGrvRow>> rowsByInvoice = new LinkedHashMap<>();

        for (int i = 1; i < lines.size(); i++) {
            int lineNumber = i + 1;
            List<String> cells = CsvImportUtils.splitCsvLine(lines.get(i), delimiter);
            String invoiceNumber = CsvImportUtils.cellAt(cells, columns.get("INVOICE"));
            String ingredientName = CsvImportUtils.cellAt(cells, columns.get("INGREDIENT"));
            String quantityRaw = CsvImportUtils.cellAt(cells, columns.get("QUANTITY"));
            String priceRaw = CsvImportUtils.cellAt(cells, columns.get("PRICE"));
            String includesVatRaw = CsvImportUtils.cellAt(cells, columns.get("INCLUDESVAT"));
            String vatCodeRaw = optionalCell(cells, columns, "VATCODE");
            String invoiceTotalRaw = optionalCell(cells, columns, "INVOICETOTAL");
            String supplierName = CsvImportUtils.cellAt(cells, columns.get("SUPPLIER"));
            String note = optionalCell(cells, columns, "NOTE");

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
            BigDecimal unitPrice = parseNonNegativeDecimal(priceRaw);
            if (unitPrice == null) {
                errors.add("Row " + lineNumber + ": unit price '" + priceRaw + "' must be a non-negative number.");
                continue;
            }
            Boolean pricesIncludeVat = parseYesNo(includesVatRaw);
            if (pricesIncludeVat == null) {
                errors.add("Row " + lineNumber + ": Prices Include VAT is required and must be Y or N.");
                continue;
            }
            VatCode vatCode = null;
            if (vatCodeRaw != null && !vatCodeRaw.isBlank()) {
                vatCode = IngredientService.parseVatCode(vatCodeRaw);
                if (vatCode == null) {
                    errors.add("Row " + lineNumber + ": VAT code '" + vatCodeRaw + "' must be one of "
                            + List.of(VatCode.values()) + ".");
                    continue;
                }
            }
            BigDecimal invoiceTotal = null;
            if (invoiceTotalRaw != null && !invoiceTotalRaw.isBlank()) {
                invoiceTotal = parseNonNegativeDecimal(invoiceTotalRaw);
                if (invoiceTotal == null) {
                    errors.add("Row " + lineNumber + ": invoice total '" + invoiceTotalRaw + "' must be a non-negative number.");
                    continue;
                }
            }
            if (supplierName == null || supplierName.isBlank()) {
                errors.add("Row " + lineNumber + ": supplier name is required.");
                continue;
            }

            var row = new ParsedGrvRow(lineNumber, invoiceNumber.trim(), ingredient.get(), quantity, unitPrice,
                    pricesIncludeVat, vatCode != null ? vatCode : ingredient.get().getDefaultVatCode(), invoiceTotal,
                    supplierName.trim(), note == null || note.isBlank() ? null : note.trim());

            var invoiceRows = rowsByInvoice.computeIfAbsent(row.invoiceNumber(), key -> new ArrayList<>());
            if (!invoiceRows.isEmpty()) {
                var first = invoiceRows.get(0);
                var mismatch = invoiceMismatch(first, row);
                if (mismatch != null) {
                    errors.add("Row " + lineNumber + ": invoice " + row.invoiceNumber() + " has " + mismatch
                            + " different from row " + first.lineNumber() + ". Rows of one invoice must agree.");
                    continue;
                }
            }
            invoiceRows.add(row);
        }

        if (!errors.isEmpty()) {
            throw new BulkImportException(errors);
        }

        var recordedBy = authService.getCurrentUser();
        List<Grv> saved = new ArrayList<>();
        for (var invoiceRows : rowsByInvoice.values()) {
            saved.add(receiveInvoice(invoiceRows, recordedBy));
        }

        var grvs = inventoryMapper.toGrvDtoList(saved);
        var invoices = grvs.stream()
                .map(grv -> new BulkGrvImportResultDto.InvoiceSummary(grv.getInvoiceNumber(), grv.getLines().size(),
                        grv.getPricesIncludeVat(), grv.getTotalExclVat(), grv.getTotalVat(), grv.getTotalInclVat(),
                        grv.getInvoiceCheck()))
                .toList();
        return new BulkGrvImportResultDto(saved.size(), grvs, invoices);
    }

    /** What, if anything, a row disagrees on with the first row of the same invoice. */
    private static String invoiceMismatch(ParsedGrvRow first, ParsedGrvRow row) {
        if (!first.supplierName().equalsIgnoreCase(row.supplierName())) {
            return "a supplier name";
        }
        if (first.pricesIncludeVat() != row.pricesIncludeVat()) {
            return "a Prices Include VAT value";
        }
        if (!sameAmount(first.invoiceTotal(), row.invoiceTotal())) {
            return "an invoice total";
        }
        return null;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private Grv receiveInvoice(List<ParsedGrvRow> rows, User recordedBy) {
        var first = rows.get(0);
        var grv = new Grv();
        grv.setInvoiceNumber(first.invoiceNumber());
        grv.setSupplierName(first.supplierName());
        grv.setNote(rows.stream().map(ParsedGrvRow::note).filter(Objects::nonNull).findFirst().orElse(null));
        grv.setPricesIncludeVat(first.pricesIncludeVat());
        grv.setInvoiceTotalInclVat(first.invoiceTotal());
        grv.setReceivedBy(recordedBy);

        for (var row : rows) {
            var line = new GrvLine();
            line.setGrv(grv);
            line.setIngredient(row.ingredient());
            line.setQuantityReceived(row.quantity());
            applyVat(line, row.pricesIncludeVat(), row.unitPrice(), row.vatCode());
            grv.getLines().add(line);
        }

        grvRepository.save(grv);
        writeReceivedMovements(grv);
        return grv;
    }

    private Location mainStore() {
        return locationRepository.findByNameIgnoreCase(RECEIVING_LOCATION)
                .orElseThrow(() -> new IllegalStateException(RECEIVING_LOCATION + " location not seeded."));
    }

    private record ParsedGrvRow(int lineNumber, String invoiceNumber, Ingredient ingredient, BigDecimal quantity,
                                BigDecimal unitPrice, boolean pricesIncludeVat, VatCode vatCode, BigDecimal invoiceTotal,
                                String supplierName, String note) {
    }

    private static String optionalCell(List<String> cells, Map<String, Integer> columns, String key) {
        return columns.containsKey(key) ? CsvImportUtils.cellAt(cells, columns.get(key)) : null;
    }

    private Map<String, Integer> parseHeader(String headerLine, String delimiter) {
        List<String> cells = CsvImportUtils.splitCsvLine(headerLine, delimiter);
        Map<String, Integer> columns = new LinkedHashMap<>();
        var hasRetiredCostColumn = false;
        for (int i = 0; i < cells.size(); i++) {
            String normalized = CsvImportUtils.normalizeHeaderCell(cells.get(i));
            // Order matters: "Invoice Total" also contains INVOICE, and "VAT Code" / "Prices
            // Include VAT" both contain VAT.
            if (normalized.contains("INVOICETOTAL")) {
                columns.put("INVOICETOTAL", i);
            } else if (normalized.contains("INVOICE")) {
                columns.put("INVOICE", i);
            } else if (normalized.contains("INGREDIENT")) {
                columns.put("INGREDIENT", i);
            } else if (normalized.equals("QUANTITY")) {
                columns.put("QUANTITY", i);
            } else if (normalized.contains("UNITPRICE")) {
                columns.put("PRICE", i);
            } else if (normalized.contains("INCLUDE") && normalized.contains("VAT")) {
                columns.put("INCLUDESVAT", i);
            } else if (normalized.contains("VATCODE")) {
                columns.put("VATCODE", i);
            } else if (normalized.contains("SUPPLIER")) {
                columns.put("SUPPLIER", i);
            } else if (normalized.contains("NOTE")) {
                columns.put("NOTE", i);
            } else if (normalized.contains("COST")) {
                hasRetiredCostColumn = true;
            }
        }
        if (hasRetiredCostColumn && !columns.containsKey("PRICE")) {
            throw new BulkImportException(List.of(
                    "The Cost Per Unit column has been replaced: use Unit Price (as written on the invoice) and Prices Include VAT (Y/N)."));
        }
        if (!columns.keySet().containsAll(List.of("INVOICE", "INGREDIENT", "QUANTITY", "PRICE", "INCLUDESVAT", "SUPPLIER"))) {
            throw new BulkImportException(List.of(
                    "CSV header must include Invoice Number, Ingredient Name, Quantity, Unit Price, Prices Include VAT and Supplier Name "
                            + "columns (VAT Code, Invoice Total and Note are optional)."));
        }
        return columns;
    }

    /** Y/N (also Yes/No, True/False); null when blank or anything else. */
    private static Boolean parseYesNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "Y", "YES", "TRUE" -> true;
            case "N", "NO", "FALSE" -> false;
            default -> null;
        };
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
