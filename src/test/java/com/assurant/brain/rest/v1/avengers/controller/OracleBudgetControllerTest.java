package com.assurant.brain.rest.v1.avengers.controller;

import com.assurant.brain.avenger.oracle.OraclePromptOptimizer;
import com.assurant.brain.avenger.oracle.OraclePromptOptimizer.RetrievalBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OracleBudgetController")
class OracleBudgetControllerTest {

    @Test
    @DisplayName("budget returns RetrievalBudget for the requested project")
    void returnsBudget() {
        OraclePromptOptimizer optimizer = mock(OraclePromptOptimizer.class);
        when(optimizer.computeBudget("ce-app")).thenReturn(new RetrievalBudget(200, 20, "HIGH", 50000, 5000, 100));
        OracleBudgetController controller = new OracleBudgetController(optimizer);

        ResponseEntity<RetrievalBudget> response = controller.budget("ce-app");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().recallK()).isEqualTo(200);
        assertThat(response.getBody().rerankK()).isEqualTo(20);
        assertThat(response.getBody().tier()).isEqualTo("HIGH");
    }
}
