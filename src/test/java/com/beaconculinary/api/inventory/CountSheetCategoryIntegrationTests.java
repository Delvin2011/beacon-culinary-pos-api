package com.beaconculinary.api.inventory;

import com.beaconculinary.api.support.AuthTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /count-sheet-categories is any authenticated role; POST/PUT under
 * /admin/count-sheet-categories are STOCK_ADMIN and ADMIN. */
@SpringBootTest
@AutoConfigureMockMvc
class CountSheetCategoryIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CountSheetCategoryRepository countSheetCategoryRepository;

    // Scoped to the names this class creates — the suite runs against a shared dev database.
    private static final List<String> TEST_CATEGORY_NAMES =
            List.of("TEST CAT SAUCES", "TEST CAT SPICES", "TEST CAT RENAMED");

    @AfterEach
    void tearDown() {
        TEST_CATEGORY_NAMES.forEach(name ->
                countSheetCategoryRepository.findByNameIgnoreCase(name)
                        .ifPresent(countSheetCategoryRepository::delete));
    }

    private String json(String name) {
        return "{\"name\":\"" + name + "\"}";
    }

    private String json(String name, boolean active) {
        return "{\"name\":\"" + name + "\",\"active\":" + active + "}";
    }

    private long createCategory(String token, String name) throws Exception {
        mockMvc.perform(post("/admin/count-sheet-categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(name)))
                .andExpect(status().isCreated());
        return countSheetCategoryRepository.findByNameIgnoreCase(name).orElseThrow().getId();
    }

    @Test
    void getAll_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/count-sheet-categories")).andExpect(status().isUnauthorized());
    }

    @Test
    void getAll_returnsSeededCategories_forAnyAuthenticatedRole() throws Exception {
        var cashierToken = AuthTestHelper.loginAsCashier(mockMvc);

        mockMvc.perform(get("/count-sheet-categories").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'POULTRY')].active").value(true));
    }

    @Test
    void create_normalizesName_andReturnsCreated() throws Exception {
        var token = AuthTestHelper.loginAsStockAdmin(mockMvc);

        mockMvc.perform(post("/admin/count-sheet-categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("  test cat \\t  sauces ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("TEST CAT SAUCES"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_duplicateNameCaseInsensitive_isBadRequest() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);

        mockMvc.perform(post("/admin/count-sheet-categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("poultry")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void create_blankName_isBadRequest() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);

        mockMvc.perform(post("/admin/count-sheet-categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(" ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_asStockClerkOrCashier_isForbidden() throws Exception {
        for (var token : List.of(AuthTestHelper.loginAsStockClerk(mockMvc), AuthTestHelper.loginAsCashier(mockMvc))) {
            mockMvc.perform(post("/admin/count-sheet-categories")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(json("TEST CAT SAUCES")))
                    .andExpect(status().isForbidden());
        }
        assertThat(countSheetCategoryRepository.findByNameIgnoreCase("TEST CAT SAUCES")).isEmpty();
    }

    @Test
    void update_renamesAndDeactivates_andGetHidesInactiveByDefault() throws Exception {
        var token = AuthTestHelper.loginAsStockAdmin(mockMvc);
        var id = createCategory(token, "TEST CAT SAUCES");

        mockMvc.perform(put("/admin/count-sheet-categories/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("test cat renamed", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("TEST CAT RENAMED"))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/count-sheet-categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'TEST CAT RENAMED')]").isEmpty());

        mockMvc.perform(get("/count-sheet-categories?includeInactive=true").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'TEST CAT RENAMED')].active").value(false));
    }

    @Test
    void update_keepingOwnName_isAllowed() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);
        var id = createCategory(token, "TEST CAT SAUCES");

        mockMvc.perform(put("/admin/count-sheet-categories/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Test Cat Sauces", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("TEST CAT SAUCES"));
    }

    @Test
    void update_toAnotherCategorysName_isBadRequest() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);
        createCategory(token, "TEST CAT SAUCES");
        var id = createCategory(token, "TEST CAT SPICES");

        mockMvc.perform(put("/admin/count-sheet-categories/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("TEST CAT SAUCES", true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_withoutActive_isBadRequest_andLeavesCategoryActive() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);
        var id = createCategory(token, "TEST CAT SAUCES");

        mockMvc.perform(put("/admin/count-sheet-categories/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("TEST CAT RENAMED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.active").value("active is required"));

        var category = countSheetCategoryRepository.findById(id).orElseThrow();
        assertThat(category.isActive()).isTrue();
        assertThat(category.getName()).isEqualTo("TEST CAT SAUCES");
    }

    @Test
    void update_unknownId_isNotFound() throws Exception {
        var token = AuthTestHelper.loginAsAdmin(mockMvc);

        mockMvc.perform(put("/admin/count-sheet-categories/999999999")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("TEST CAT SAUCES", true)))
                .andExpect(status().isNotFound());
    }
}
