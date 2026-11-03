package com.beaconculinary.api.menu;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Component categories (V100) and the category/name filters on the component catalogue. */
@SpringBootTest
@AutoConfigureMockMvc
class ComponentCategoryIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private ComponentCategoryRepository componentCategoryRepository;

    private String adminToken;
    private String cashierToken;
    private final List<Long> componentIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        cashierToken = AuthTestHelper.loginAsCashier(mockMvc);
    }

    @AfterEach
    void tearDown() {
        componentCatalogRepository.deleteAllById(componentIds);
        componentCategoryRepository.deleteAllById(categoryIds);
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private long createCategory(String name) throws Exception {
        var response = mockMvc.perform(post("/admin/component-categories").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"sortOrder\":50}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();
        categoryIds.add(id);
        return id;
    }

    private long createComponent(String name, Long categoryId) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"extraPrice\":10.00"
                + (categoryId == null ? "" : ",\"categoryId\":" + categoryId) + "}";
        var response = mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();
        componentIds.add(id);
        return id;
    }

    @Test
    void seededCategories_areListedInSortOrder() throws Exception {
        mockMvc.perform(get("/admin/component-categories").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Meat"))
                .andExpect(jsonPath("$[?(@.name=='Fish')]").exists())
                .andExpect(jsonPath("$[?(@.name=='Starch')]").exists())
                .andExpect(jsonPath("$[?(@.name=='Sauces & gravies')]").exists());
    }

    @Test
    void componentWithCategory_returnsIt_andFiltersByCategoryAndName() throws Exception {
        var categoryId = createCategory(unique("Seafood"));
        var hake = unique("Hake");
        createComponent(hake, categoryId);
        createComponent(unique("Prawns"), categoryId);
        createComponent(unique("Hake-ish but uncategorised"), null);

        mockMvc.perform(get("/admin/component-catalog").param("categoryId", String.valueOf(categoryId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].category.id").value(categoryId));

        mockMvc.perform(get("/admin/component-catalog").param("categoryId", String.valueOf(categoryId))
                        .param("name", hake.substring(0, 8).toUpperCase())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value(hake));

        mockMvc.perform(get("/admin/component-catalog").param("uncategorised", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category != null)]").isEmpty());
    }

    @Test
    void updateWithoutCategoryId_keepsCategory_andWithOne_movesIt() throws Exception {
        var first = createCategory(unique("First"));
        var second = createCategory(unique("Second"));
        var id = createComponent(unique("Samp"), first);

        mockMvc.perform(put("/admin/component-catalog/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Samp renamed\",\"extraPrice\":12.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.id").value(first));

        mockMvc.perform(put("/admin/component-catalog/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Samp renamed\",\"extraPrice\":12.00,\"categoryId\":" + second + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.id").value(second));
    }

    @Test
    void unknownCategory_returns400CategoryInvalid() throws Exception {
        mockMvc.perform(post("/admin/component-catalog").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"extraPrice\":1.00,\"categoryId\":999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_INVALID"));
    }

    @Test
    void duplicateName_returns409_andDeactivatingWithActiveComponents_returns409() throws Exception {
        var name = unique("Pulses");
        var categoryId = createCategory(name);
        mockMvc.perform(post("/admin/component-categories").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name.toLowerCase() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));

        createComponent(unique("Beans"), categoryId);
        mockMvc.perform(put("/admin/component-categories/" + categoryId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_ITEMS"));
    }

    @Test
    void cashier_cannotManageCategories() throws Exception {
        mockMvc.perform(get("/admin/component-categories").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/component-categories").header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }
}
