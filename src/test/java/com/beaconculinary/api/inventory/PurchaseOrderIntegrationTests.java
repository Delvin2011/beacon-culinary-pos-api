package com.beaconculinary.api.inventory;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises Stage 5 Part G: deliberately lightweight, purely informational purchase orders. */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseOrderIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired
    private IngredientRepository ingredientRepository;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        purchaseOrderRepository.deleteAll();
        ingredientRepository.deleteAll();
    }

    private long createIngredient(String name) throws Exception {
        var body = "{\"name\":\"" + name + "\",\"unit\":\"KG\",\"countSheetCategory\":\"DRYSTOCK\"}";
        var response = mockMvc.perform(post("/admin/ingredients").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    @Test
    void createPurchaseOrder_startsAsDraft_appearsInList() throws Exception {
        var ingredientId = createIngredient("Rice");

        var body = "{\"supplierName\":\"Acme Foods\",\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantity\":25.0}]}";
        var response = mockMvc.perform(post("/admin/purchase-orders").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();

        mockMvc.perform(get("/admin/purchase-orders").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].supplierName").value("Acme Foods"));
    }

    @Test
    void updateStatus_transitionsDraftToSubmittedToReceived() throws Exception {
        var ingredientId = createIngredient("Rice");
        var createBody = "{\"supplierName\":\"Acme Foods\",\"lines\":[{\"ingredientId\":" + ingredientId + ",\"quantity\":25.0}]}";
        var response = mockMvc.perform(post("/admin/purchase-orders").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(response).get("id").asLong();

        mockMvc.perform(put("/admin/purchase-orders/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mockMvc.perform(put("/admin/purchase-orders/" + id).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RECEIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void updateStatus_onNonexistentPurchaseOrder_returns404() throws Exception {
        mockMvc.perform(put("/admin/purchase-orders/999999999").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createPurchaseOrder_withUnknownIngredientId_returns400() throws Exception {
        var body = "{\"supplierName\":\"Acme Foods\",\"lines\":[{\"ingredientId\":999999999,\"quantity\":25.0}]}";
        mockMvc.perform(post("/admin/purchase-orders").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
