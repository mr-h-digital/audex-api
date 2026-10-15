package za.co.mrhdigital.audex.scoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import za.co.mrhdigital.audex.api.ScoringController;

@WebMvcTest(value = ScoringController.class, properties = "audex.auth.enabled=false")
@Import(TemplateCatalog.class)
class ScoringControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void listsBothRuleSets() throws Exception {
        mvc.perform(get("/api/rulesets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("WPC_V1"))
                .andExpect(jsonPath("$[1].majorFailureCap.count").value(3));
    }

    @Test
    void emptyAnswersAreIncomplete() throws Exception {
        mvc.perform(post("/api/scoring/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleSet\":\"WPC_V2_PROPOSED\",\"answers\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.unanswered").value(264));
    }

    @Test
    void rejectsUnknownRuleSetAndItem() throws Exception {
        mvc.perform(post("/api/scoring/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleSet\":\"NOPE\",\"answers\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/scoring/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleSet\":\"WPC_V1\",\"answers\":{\"made-up-01\":\"COMPLIANT\"}}"))
                .andExpect(status().isBadRequest());
    }
}
