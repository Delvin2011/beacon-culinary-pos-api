package com.beaconculinary.api.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Category ids from the seeded catalogue tree (V96/V97), via the real GET
 * /admin/catalog/categories. Every meal needs a categoryId; set meals use "Set meals". Cached per
 * test JVM, since the seeded categories never change. */
public final class CatalogTestHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Long> IDS_BY_PATH = new ConcurrentHashMap<>();

    private CatalogTestHelper() {
    }

    public static long setMealsId(MockMvc mockMvc) throws Exception {
        return categoryId(mockMvc, "Set meals");
    }

    /** A category by path: "Set meals", "Light meals > Burgers", "Beverages > Hot beverages". */
    public static long categoryId(MockMvc mockMvc, String path) throws Exception {
        var cached = IDS_BY_PATH.get(path);
        if (cached != null) {
            return cached;
        }
        var token = AuthTestHelper.loginAsAdmin(mockMvc);
        var response = mockMvc.perform(get("/admin/catalog/categories").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode top : MAPPER.readTree(response)) {
            IDS_BY_PATH.put(top.get("name").asText(), top.get("id").asLong());
            for (JsonNode child : top.get("children")) {
                IDS_BY_PATH.put(top.get("name").asText() + " > " + child.get("name").asText(), child.get("id").asLong());
            }
        }
        var id = IDS_BY_PATH.get(path);
        if (id == null) {
            throw new IllegalStateException("Category not seeded: " + path);
        }
        return id;
    }
}
