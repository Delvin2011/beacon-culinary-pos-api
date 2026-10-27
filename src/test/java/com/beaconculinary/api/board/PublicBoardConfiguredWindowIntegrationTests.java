package com.beaconculinary.api.board;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** board.done-display-minutes is read from configuration (env BOARD_DONE_DISPLAY_MINUTES) and
 * reported to the board. */
@SpringBootTest(properties = "board.done-display-minutes=3")
@AutoConfigureMockMvc
class PublicBoardConfiguredWindowIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void configuredWindowIsUsed() throws Exception {
        mockMvc.perform(get("/public/board/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doneDisplayMinutes").value(3));
    }
}
