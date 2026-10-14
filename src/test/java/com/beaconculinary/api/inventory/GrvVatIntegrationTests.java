package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GRV VAT handling (v2): capture as invoiced, VAT added where needed, ledger cost VAT-inclusive. */
@SpringBootTest
@AutoConfigureMockMvc
class GrvVatIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private CountSheetCategoryRepository countSheetCategoryRepository;

    private String adminToken;
    private final List<Long> ingredientIds = new ArrayList<>();
    private long beefId;
    private long riceId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        beefId = ingredient("VAT Test Beef", VatCode.STANDARD);
        riceId = ingredient("VAT Test Rice", VatCode.ZERO_RATED);
    }

    private long ingredient(String name, VatCode defaultVatCode) {
        var ingredient = new Ingredient();
        ingredient.setName(name);
        ingredient.setUnit(IngredientUnit.KG);
        ingredient.setCountSheetCategory(countSheetCategoryRepository.findByNameIgnoreCase("DRYSTOCK").orElseThrow());
        ingredient.setDefaultVatCode(defaultVatCode);
        var id = ingredientRepository.save(ingredient).getId();
        ingredientIds.add(id);
        return id;
    }

    // Scoped to this class's own ingredients: the suite shares a dev database.
    @AfterEach
    void tearDown() {
        for (var id : ingredientIds) {
            ingredientStockMovementRepository.findAll().stream()
                    .filter(m -> id.equals(m.getIngredient().getId()))
                    .forEach(ingredientStockMovementRepository::delete);
            grvRepository.search(id, null, null, null).forEach(grvRepository::delete);
        }
        ingredientIds.forEach(ingredientRepository::deleteById);
        ingredientIds.clear();
    }

    private ResultActions postGrv(String body) throws Exception {
        return mockMvc.perform(post("/admin/grv").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String grv(String invoice, Boolean pricesIncludeVat, String invoiceTotal, String... lines) {
        return "{\"invoiceNumber\":\"" + invoice + "\""
                + (pricesIncludeVat == null ? "" : ",\"pricesIncludeVat\":" + pricesIncludeVat)
                + (invoiceTotal == null ? "" : ",\"invoiceTotalInclVat\":" + invoiceTotal)
                + ",\"supplierName\":\"Acme Foods\",\"lines\":[" + String.join(",", lines) + "]}";
    }

    private static String line(long ingredientId, String quantity, String unitPrice, String vatCode) {
        return "{\"ingredientId\":" + ingredientId + ",\"quantityReceived\":" + quantity + ",\"unitPrice\":" + unitPrice
                + (vatCode == null ? "" : ",\"vatCode\":\"" + vatCode + "\"") + "}";
    }

    private JsonNode created(String body) throws Exception {
        return MAPPER.readTree(postGrv(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private BigDecimal movementCost(long grvLineId) {
        return ingredientStockMovementRepository.findBySourceTypeAndSourceId(MovementSourceType.GRV, grvLineId)
                .orElseThrow().getCostPerUnit();
    }

    @Test
    void theFiveWorkedExamples_viaTheApi() throws Exception {
        record Example(boolean incl, String code, String qty, String price, String excl, String vat, String inclTotal, String cost) {
        }
        var examples = List.of(
                new Example(false, "STANDARD", "10", "100.00", "1000.00", "150.00", "1150.00", "115.0000"),
                new Example(true, "STANDARD", "10", "115.00", "1000.00", "150.00", "1150.00", "115.0000"),
                new Example(false, "ZERO_RATED", "10", "50.00", "500.00", "0.00", "500.00", "50.0000"),
                new Example(true, "STANDARD", "50", "18.00", "782.61", "117.39", "900.00", "18.0000"),
                new Example(false, "STANDARD", "3", "10.01", "30.03", "4.50", "34.53", "11.5100"));

        var n = 0;
        for (var e : examples) {
            var node = created(grv("INV-WE-" + (++n), e.incl(), null, line(beefId, e.qty(), e.price(), e.code())));
            var l = node.get("lines").get(0);
            assertThat(l.get("lineExclVat").decimalValue()).isEqualByComparingTo(e.excl());
            assertThat(l.get("lineVat").decimalValue()).isEqualByComparingTo(e.vat());
            assertThat(l.get("lineInclVat").decimalValue()).isEqualByComparingTo(e.inclTotal());
            assertThat(l.get("costPerUnit").decimalValue()).isEqualByComparingTo(e.cost());
            assertThat(l.get("unitCostInclVat").decimalValue()).isEqualByComparingTo(e.cost());
            assertThat(l.get("vatCode").asText()).isEqualTo(e.code());
            // The ledger movement carries the 4 dp VAT-inclusive cost.
            assertThat(movementCost(l.get("id").asLong())).isEqualTo(new BigDecimal(e.cost()));
            assertThat(node.get("totalInclVat").decimalValue()).isEqualByComparingTo(e.inclTotal());
        }
    }

    @Test
    void samePurchaseWrittenBothWays_givesIdenticalLedgerCostAndStockValue() throws Exception {
        var exclusiveBeef = ingredient("VAT Test Beef Exclusive", VatCode.STANDARD);
        var inclusiveBeef = ingredient("VAT Test Beef Inclusive", VatCode.STANDARD);
        var excl = created(grv("INV-EX", false, null, line(exclusiveBeef, "10", "100.00", null))).get("lines").get(0);
        var incl = created(grv("INV-IN", true, null, line(inclusiveBeef, "10", "115.00", null))).get("lines").get(0);

        assertThat(movementCost(excl.get("id").asLong())).isEqualTo(movementCost(incl.get("id").asLong()));
        var stockValue = new BigDecimal("10").multiply(movementCost(excl.get("id").asLong()));
        assertThat(stockValue).isEqualByComparingTo("1150.00");
        assertThat(excl.get("lineInclVat").decimalValue()).isEqualByComparingTo(incl.get("lineInclVat").decimalValue());
    }

    @Test
    void vatCode_fallsBackToTheIngredientDefault_andAnExplicitCodeOverridesIt() throws Exception {
        var node = created(grv("INV-CODES", false, null,
                line(riceId, "10", "50.00", null),            // ingredient default: ZERO_RATED
                line(riceId, "10", "50.00", "STANDARD"),      // explicit override
                line(beefId, "10", "100.00", null)));         // ingredient default: STANDARD

        var lines = node.get("lines");
        assertThat(lines.get(0).get("vatCode").asText()).isEqualTo("ZERO_RATED");
        assertThat(lines.get(0).get("vatRate").decimalValue()).isEqualByComparingTo("0");
        assertThat(lines.get(1).get("vatCode").asText()).isEqualTo("STANDARD");
        assertThat(lines.get(1).get("vatRate").decimalValue()).isEqualByComparingTo("0.15");
        assertThat(lines.get(1).get("lineVat").decimalValue()).isEqualByComparingTo("75.00");
        assertThat(lines.get(2).get("vatCode").asText()).isEqualTo("STANDARD");
        // Header totals = sum of the lines: excl 500 + 500 + 1000; VAT 0 + 75 + 150.
        assertThat(node.get("totalExclVat").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(node.get("totalVat").decimalValue()).isEqualByComparingTo("225.00");
        assertThat(node.get("totalInclVat").decimalValue()).isEqualByComparingTo("2225.00");
        assertThat(node.get("pricesIncludeVat").asBoolean()).isFalse();
    }

    @Test
    void missingPricesIncludeVat_orARetiredCostPerUnit_is400() throws Exception {
        postGrv(grv("INV-NOBASIS", null, null, line(beefId, "10", "100.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.pricesIncludeVat").value("pricesIncludeVat is required"));
        postGrv("{\"invoiceNumber\":\"INV-OLD\",\"pricesIncludeVat\":true,\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + beefId + ",\"quantityReceived\":10,\"unitPrice\":100.00,\"costPerUnit\":100.00}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("costPerUnit is no longer accepted")));
        postGrv("{\"invoiceNumber\":\"INV-OLDER\",\"pricesIncludeVat\":true,\"supplierName\":\"Acme Foods\",\"lines\":["
                + "{\"ingredientId\":" + beefId + ",\"quantityReceived\":10,\"costPerUnit\":100.00}]}")
                .andExpect(status().isBadRequest());
        assertThat(grvRepository.search(beefId, null, null, null)).isEmpty();
    }

    @Test
    void invoiceCheck_passWithinFiveCents_warnBeyond_nullWithoutAPrintedTotal_andNeverBlocks() throws Exception {
        // Computed total incl VAT = 1150.00.
        postGrv(grv("INV-CHK-1", false, "1150.02", line(beefId, "10", "100.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceTotalInclVat").value(1150.02))
                .andExpect(jsonPath("$.invoiceCheck.status").value("PASS"))
                .andExpect(jsonPath("$.invoiceCheck.difference").value(0.02));
        postGrv(grv("INV-CHK-2", false, "1155.00", line(beefId, "10", "100.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceCheck.status").value("WARN"))
                .andExpect(jsonPath("$.invoiceCheck.difference").value(5.00));
        postGrv(grv("INV-CHK-3", false, null, line(beefId, "10", "100.00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceCheck").value(nullValue()));
    }

    @Test
    void sameDayEdit_fromExclusiveToInclusive_recomputesEverythingAndTheMovement() throws Exception {
        var node = created(grv("INV-EDIT", false, null, line(beefId, "10", "100.00", null)));
        var id = node.get("id").asLong();
        var lineId = node.get("lines").get(0).get("id").asLong();
        assertThat(movementCost(lineId)).isEqualByComparingTo("115.0000");

        // Same goods, now written as an inclusive R100.00 per unit, 12 units, zero-rated.
        var patchBody = "{\"invoiceNumber\":\"INV-EDIT\",\"pricesIncludeVat\":true,\"invoiceTotalInclVat\":1200.00,"
                + "\"supplierName\":\"Acme Foods\",\"lines\":[{\"id\":" + lineId
                + ",\"quantityReceived\":12,\"unitPrice\":100.00,\"vatCode\":\"ZERO_RATED\"}]}";
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricesIncludeVat").value(true))
                .andExpect(jsonPath("$.lines[0].vatCode").value("ZERO_RATED"))
                .andExpect(jsonPath("$.lines[0].lineExclVat").value(1200.00))
                .andExpect(jsonPath("$.lines[0].lineVat").value(0))
                .andExpect(jsonPath("$.lines[0].lineInclVat").value(1200.00))
                .andExpect(jsonPath("$.lines[0].costPerUnit").value(100.00))
                .andExpect(jsonPath("$.invoiceCheck.status").value("PASS"));

        var movement = ingredientStockMovementRepository.findBySourceTypeAndSourceId(MovementSourceType.GRV, lineId).orElseThrow();
        assertThat(movement.getQuantity()).isEqualByComparingTo("12");
        assertThat(movement.getCostPerUnit()).isEqualByComparingTo("100.0000");
    }

    @Test
    void sameDayEdit_withoutAVatCode_keepsTheLinesCurrentCode() throws Exception {
        var node = created(grv("INV-KEEP", false, null, line(beefId, "10", "100.00", "ZERO_RATED")));
        var lineId = node.get("lines").get(0).get("id").asLong();

        mockMvc.perform(patch("/admin/grv/" + node.get("id").asLong()).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceNumber\":\"INV-KEEP\",\"pricesIncludeVat\":false,\"supplierName\":\"Acme Foods\","
                                + "\"lines\":[{\"id\":" + lineId + ",\"quantityReceived\":10,\"unitPrice\":90.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].vatCode").value("ZERO_RATED"))
                .andExpect(jsonPath("$.lines[0].lineInclVat").value(900.00));
    }

    @Test
    void legacyGrv_readsAsInclusiveWithNoVatSplit_andASameDayEditRecomputesEverything() throws Exception {
        var node = created(grv("INV-LEGACY", true, null, line(beefId, "4", "15.6522", null)));
        var id = node.get("id").asLong();
        var lineId = node.get("lines").get(0).get("id").asLong();
        // What V88 leaves on a GRV captured before VAT handling: inclusive, with no VAT split.
        jdbc.update("UPDATE grv_lines SET vat_code = NULL, vat_rate = NULL, unit_cost_excl_vat = NULL, line_excl_vat = NULL, "
                + "line_vat = NULL, line_incl_vat = ROUND(quantity_received * cost_per_unit, 2) WHERE id = ?", lineId);
        var costBefore = movementCost(lineId);

        mockMvc.perform(get("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricesIncludeVat").value(true))
                .andExpect(jsonPath("$.lines[0].lineInclVat").value(62.61))
                .andExpect(jsonPath("$.lines[0].lineExclVat").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].lineVat").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].vatCode").value(nullValue()))
                .andExpect(jsonPath("$.lines[0].costPerUnit").value(15.6522))
                .andExpect(jsonPath("$.totalInclVat").value(62.61))
                .andExpect(jsonPath("$.totalExclVat").value(nullValue()))
                .andExpect(jsonPath("$.totalVat").value(nullValue()));
        // No ledger value changed.
        assertThat(movementCost(lineId)).isEqualByComparingTo(costBefore);

        // A same-day edit recomputes every field; the VAT code comes from the ingredient default.
        mockMvc.perform(patch("/admin/grv/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceNumber\":\"INV-LEGACY\",\"pricesIncludeVat\":true,\"supplierName\":\"Acme Foods\","
                                + "\"lines\":[{\"id\":" + lineId + ",\"quantityReceived\":4,\"unitPrice\":15.6522}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].vatCode").value("STANDARD"))
                .andExpect(jsonPath("$.lines[0].lineExclVat").value(54.44))
                .andExpect(jsonPath("$.lines[0].lineVat").value(8.17))
                .andExpect(jsonPath("$.totalExclVat").value(54.44));
    }

    @Test
    void vatSettings_forAdminAndStockAdmin_only() throws Exception {
        for (var token : List.of(adminToken, AuthTestHelper.loginAsStockAdmin(mockMvc))) {
            mockMvc.perform(get("/admin/settings/vat").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.standardRate").value(0.15));
        }
        mockMvc.perform(get("/admin/settings/vat").header("Authorization", "Bearer " + AuthTestHelper.loginAsCashier(mockMvc)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ingredientDefaultVatCode_setOnCreate_keptWhenUpdateLeavesItOut() throws Exception {
        var categoryId = countSheetCategoryRepository.findByNameIgnoreCase("DRYSTOCK").orElseThrow().getId();
        var created = MAPPER.readTree(mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"VAT Test Samp\",\"unit\":\"KG\",\"countSheetCategoryId\":" + categoryId
                                + ",\"defaultVatCode\":\"ZERO_RATED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultVatCode").value("ZERO_RATED"))
                .andReturn().getResponse().getContentAsString());
        var id = created.get("id").asLong();
        ingredientIds.add(id);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/admin/ingredients/" + id)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"VAT Test Samp\",\"unit\":\"KG\",\"countSheetCategoryId\":" + categoryId + ",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVatCode").value("ZERO_RATED"));

        mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"VAT Test Salt\",\"unit\":\"KG\",\"countSheetCategoryId\":" + categoryId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultVatCode").value("STANDARD"))
                .andDo(result -> ingredientIds.add(MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asLong()));
    }
}
