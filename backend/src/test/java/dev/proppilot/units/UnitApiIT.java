package dev.proppilot.units;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.proppilot.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class UnitApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listsUnitsWithPaging() throws Exception {
        mvc.perform(get("/api/units?size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(150))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void filtersByCityAndStatus() throws Exception {
        mvc.perform(get("/api/units?city=Riyadh&status=VACANT&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].cityEn").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("Riyadh"))))
                .andExpect(jsonPath("$.items[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("VACANT"))));
    }

    @Test
    void filtersByArabicCityName() throws Exception {
        mvc.perform(get("/api/units?city=جدة&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(50));
    }

    @Test
    void returnsSingleUnitCaseInsensitive() throws Exception {
        mvc.perform(get("/api/units/a-203"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A-203"));
    }

    @Test
    void unknownUnitIs404() throws Exception {
        mvc.perform(get("/api/units/Z-999")).andExpect(status().isNotFound());
    }

    @Test
    void invalidStatusIs400() throws Exception {
        mvc.perform(get("/api/units?status=BOGUS")).andExpect(status().isBadRequest());
    }
}
