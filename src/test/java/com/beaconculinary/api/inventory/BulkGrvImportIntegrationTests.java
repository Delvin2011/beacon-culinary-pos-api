package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GRV bulk upload with VAT handling: rows sharing an invoice number become one GRV; Prices
 * Include VAT is required; per-invoice totals and the invoice check are reported. */
@SpringBootTest
@AutoConfigureMockMvc
class BulkGrvImportIntegrationTests {
    private static final String HEADER =
            "Invoice Number,Ingredient Name,Quantity,Unit Price,Prices Include VAT,VAT Code,Invoice Total,Supplier Name,Note (Optional)";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private IngredientRepository ingredientRepository;
    @Autowired
    private GrvRepository grvRepository;
    @Autowired
    private IngredientStockMovementRepository ingredientStockMovementRepository;
    @Autowired
    private CountSheetCategoryRepository countSheetCategoryRepository;

    private String adminToken;
    private Long chickenId;
    private Long beefId;
    private Long riceId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        chickenId = createIngredient("Bulk GRV Test Chicken", VatCode.STANDARD);
        beefId = createIngredient("Bulk GRV Test Beef", VatCode.STANDARD);
        riceId = createIngredient("Bulk GRV Test Rice", VatCode.ZERO_RATED);
    }

    private Long createIngredient(String name, VatCode defaultVatCode) {
        var ingredient = new Ingredient();
        ingredient.setName(name);
        ingredient.setUnit(IngredientUnit.KG);
        ingredient.setCountSheetCategory(countSheetCategoryRepository.findByNameIgnoreCase("MEAT").orElseThrow());
        ingredient.setDefaultVatCode(defaultVatCode);
        return ingredientRepository.save(ingredient).getId();
    }

    // Scoped to exactly the ingredients this test class creates (findAll+filter rather than a
    // blanket deleteAll) — this suite runs against a shared dev database that can hold real
    // GRV/stock-movement history on other ingredients, which must never be touched.
    @AfterEach
    void tearDown() {
        for (Long id : List.of(chickenId, beefId, riceId)) {
            ingredientStockMovementRepository.findAll().stream()
                    .filter(m -> id.equals(m.getIngredient().getId()))
                    .forEach(ingredientStockMovementRepository::delete);
            grvRepository.search(id, null, null, null).forEach(grvRepository::delete);
        }
        for (Long id : List.of(chickenId, beefId, riceId)) {
            ingredientRepository.deleteById(id);
        }
    }

    private ResultActions upload(String body) throws Exception {
        var file = new MockMultipartFile("file", "grv.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/admin/grv/bulk-import").file(file).header("Authorization", "Bearer " + adminToken));
    }

    @Test
    void rowsSharingAnInvoiceNumber_becomeOneGrv_withPerInvoiceTotalsAndCheck() throws Exception {
        // INV-M: standard-rated beef and zero-rated rice on one VAT-exclusive invoice.
        //   beef 10 x 100.00 excl = 1000.00 + 150.00 VAT = 1150.00; rice 10 x 50.00 = 500.00, no VAT.
        upload(HEADER + "\n"
                + "INV-M,Bulk GRV Test Beef,10,100.00,N,STANDARD,1650.02,Acme Foods,Weekly order\n"
                + "INV-M,Bulk GRV Test Rice,10,50.00,N,,1650.02,Acme Foods,\n"
                + "INV-O,Bulk GRV Test Chicken,50,18.00,Y,,905.00,Fresh Farms,\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.grvs[0].invoiceNumber").value("INV-M"))
                .andExpect(jsonPath("$.grvs[0].lines.length()").value(2))
                .andExpect(jsonPath("$.grvs[0].note").value("Weekly order"))
                .andExpect(jsonPath("$.grvs[0].pricesIncludeVat").value(false))
                .andExpect(jsonPath("$.grvs[0].lines[1].vatCode").value("ZERO_RATED"))
                .andExpect(jsonPath("$.invoices[0].invoiceNumber").value("INV-M"))
                .andExpect(jsonPath("$.invoices[0].lineCount").value(2))
                .andExpect(jsonPath("$.invoices[0].totalExclVat").value(1500.00))
                .andExpect(jsonPath("$.invoices[0].totalVat").value(150.00))
                .andExpect(jsonPath("$.invoices[0].totalInclVat").value(1650.00))
                .andExpect(jsonPath("$.invoices[0].invoiceCheck.status").value("PASS"))
                .andExpect(jsonPath("$.invoices[0].invoiceCheck.difference").value(0.02))
                // INV-O: inclusive 50 x 18.00 = 900.00, printed 905.00 -> off by R5, WARN (not blocked).
                .andExpect(jsonPath("$.invoices[1].totalExclVat").value(782.61))
                .andExpect(jsonPath("$.invoices[1].totalVat").value(117.39))
                .andExpect(jsonPath("$.invoices[1].totalInclVat").value(900.00))
                .andExpect(jsonPath("$.invoices[1].invoiceCheck.status").value("WARN"))
                .andExpect(jsonPath("$.invoices[1].invoiceCheck.difference").value(5.00));

        mockMvc.perform(get("/admin/ingredients/" + beefId + "/stock").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.totalStock").value(10));
    }

    @Test
    void theFiveWorkedExamples_asUploadRows() throws Exception {
        upload(HEADER + "\n"
                + "INV-W1,Bulk GRV Test Beef,10,100.00,N,STANDARD,,Acme Foods,\n"
                + "INV-W2,Bulk GRV Test Beef,10,115.00,Y,STANDARD,,Acme Foods,\n"
                + "INV-W3,Bulk GRV Test Rice,10,50.00,N,ZERO_RATED,,Acme Foods,\n"
                + "INV-W4,Bulk GRV Test Chicken,50,18.00,Y,STANDARD,,Acme Foods,\n"
                + "INV-W5,Bulk GRV Test Chicken,3,10.01,N,STANDARD,,Acme Foods,\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(5))
                .andExpect(jsonPath("$.grvs[*].lines[0].lineExclVat", org.hamcrest.Matchers.contains(1000.0, 1000.0, 500.0, 782.61, 30.03)))
                .andExpect(jsonPath("$.grvs[*].lines[0].lineVat", org.hamcrest.Matchers.contains(150.0, 150.0, 0.0, 117.39, 4.5)))
                .andExpect(jsonPath("$.grvs[*].lines[0].lineInclVat", org.hamcrest.Matchers.contains(1150.0, 1150.0, 500.0, 900.0, 34.53)))
                .andExpect(jsonPath("$.grvs[*].lines[0].costPerUnit", org.hamcrest.Matchers.contains(115.0, 115.0, 50.0, 18.0, 11.51)))
                .andExpect(jsonPath("$.invoices[0].invoiceCheck").value(nullValue()));
    }

    @Test
    void mixedValuesWithinOneInvoice_areRowLevelErrors_andNothingIsSaved() throws Exception {
        upload(HEADER + "\n"
                + "INV-X,Bulk GRV Test Beef,10,100.00,N,,1150.00,Acme Foods,\n"
                + "INV-X,Bulk GRV Test Rice,10,50.00,Y,,1150.00,Acme Foods,\n"
                + "INV-X,Bulk GRV Test Chicken,1,10.00,N,,999.00,Acme Foods,\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Row 3")))
                .andExpect(jsonPath("$.errors[0]").value(containsString("Prices Include VAT")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("Row 4")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("invoice total")));

        assertThat(grvRepository.search(beefId, null, null, null)).isEmpty();
    }

    @Test
    void missingPricesIncludeVat_andInvalidVatCode_areRowLevelErrors() throws Exception {
        upload(HEADER + "\n"
                + "INV-A,Bulk GRV Test Beef,10,100.00,,,,Acme Foods,\n"
                + "INV-B,Bulk GRV Test Beef,10,100.00,N,LUXURY,,Acme Foods,\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Row 2: Prices Include VAT is required")))
                .andExpect(jsonPath("$.errors[1]").value(containsString("Row 3: VAT code 'LUXURY'")));
    }

    @Test
    void unknownIngredient_rejectsWholeFileAndSavesNothing() throws Exception {
        upload(HEADER + "\n"
                + "INV-001,Bulk GRV Test Chicken,80,42,Y,,,Acme Foods,\n"
                + "INV-002,Nonexistent Ingredient,60,95,Y,,,Acme Foods,\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Nonexistent Ingredient")));

        assertThat(grvRepository.search(chickenId, null, null, null)).isEmpty();
    }

    @Test
    void invalidQuantity_andMissingInvoiceNumber_return400() throws Exception {
        upload(HEADER + "\n" + "INV-001,Bulk GRV Test Chicken,not-a-number,42,Y,,,Acme Foods,\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("quantity")));
        upload(HEADER + "\n" + ",Bulk GRV Test Chicken,80,42,Y,,,Acme Foods,\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("invoice number")));
    }

    @Test
    void optionalColumnsCanBeLeftOut() throws Exception {
        upload("Invoice Number,Ingredient Name,Quantity,Unit Price,Prices Include VAT,Supplier Name\n"
                + "INV-001,Bulk GRV Test Chicken,80,42,Y,Acme Foods\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.grvs[0].note").value(nullValue()))
                .andExpect(jsonPath("$.grvs[0].lines[0].vatCode").value("STANDARD"))
                .andExpect(jsonPath("$.grvs[0].invoiceCheck").value(nullValue()));
    }

    @Test
    void missingRequiredColumn_andTheRetiredCostColumn_return400() throws Exception {
        upload("Invoice Number,Ingredient Name,Quantity,Unit Price,Supplier Name\n"
                + "INV-001,Bulk GRV Test Chicken,80,42,Acme Foods\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Prices Include VAT")));
        upload("Invoice Number,Ingredient Name,Quantity,Cost Per Unit,Supplier Name\n"
                + "INV-001,Bulk GRV Test Chicken,80,42,Acme Foods\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(containsString("Cost Per Unit column has been replaced")));
    }
}
