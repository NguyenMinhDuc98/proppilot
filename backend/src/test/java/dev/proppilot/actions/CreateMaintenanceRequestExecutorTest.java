package dev.proppilot.actions;

import static dev.proppilot.actions.Fixtures.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.proppilot.agent.tools.ToolInputException;
import dev.proppilot.domain.MaintenanceCategory;
import dev.proppilot.domain.MaintenancePriority;
import dev.proppilot.domain.MaintenanceRequestRepository;
import dev.proppilot.domain.UnitRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CreateMaintenanceRequestExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UnitRepository units = mock(UnitRepository.class);
    private final CreateMaintenanceRequestExecutor executor =
            new CreateMaintenanceRequestExecutor(units, mock(MaintenanceRequestRepository.class), JSON);

    @BeforeEach
    void oneUnitExists() {
        when(units.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(units.findByCodeIgnoreCase("a-203")).thenReturn(Optional.of(unit("A-203")));
    }

    private static ObjectNode args() {
        return JSON.createObjectNode().put("unit_code", "a-203").put("category", "plumbing").put("priority", "urgent")
                .put("description", "Water is leaking from the ceiling");
    }

    @Test
    void normalizesTheArguments() {
        var validated = executor.validate(args());

        assertThat(validated.unit().getCode()).isEqualTo("A-203");
        assertThat(validated.category()).isEqualTo(MaintenanceCategory.PLUMBING);
        assertThat(validated.priority()).isEqualTo(MaintenancePriority.URGENT);
        assertThat(executor.arguments(validated).toString())
                .contains("\"unit_code\":\"A-203\"", "\"category\":\"PLUMBING\"", "\"priority\":\"URGENT\"");
    }

    @Test
    void theUnitMustExist() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(ActionException.class,
                () -> executor.validate(args().put("unit_code", "Z-999")));

        assertThat(thrown.error()).isEqualTo(ActionError.UNKNOWN_UNIT);
    }

    @Test
    void theDescriptionIsCleanedBeforeItIsMeasuredAndStored() {
        var validated = executor.validate(args().put("description", "  Door\u0000 handle\n\n is   \tbroken\u007f  "));

        assertThat(validated.description()).isEqualTo("Door handle is broken");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "    ", "abcd", "ab\nc", "\u0001\u0002\u0003\u0004\u0005\u0006"})
    void aDescriptionNeedsAtLeastFiveCharactersOnceCleaned(String description) {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(Throwable.class,
                () -> executor.validate(args().put("description", description)));

        assertThat(thrown).isInstanceOfAny(ActionException.class, ToolInputException.class);
    }

    @Test
    void aDescriptionIsAtMostFiveHundredCharacters() {
        assertThat(executor.validate(args().put("description", "ش".repeat(500))).description()).hasSize(500);

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(ActionException.class,
                () -> executor.validate(args().put("description", "x".repeat(501))));
        assertThat(thrown.error()).isEqualTo(ActionError.INVALID_ARGUMENT);
    }

    @Test
    void categoryAndPriorityMustBeKnownValues() {
        assertThatThrownBy(() -> executor.validate(args().put("category", "GARDEN")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("category must be one of");
        assertThatThrownBy(() -> executor.validate(args().put("priority", "SOON")))
                .isInstanceOf(ToolInputException.class).hasMessageContaining("priority must be one of");
        assertThatThrownBy(() -> executor.validate(args().without("category")))
                .isInstanceOf(ToolInputException.class).hasMessage("category is required");
        assertThatThrownBy(() -> executor.validate(args().without("unit_code")))
                .isInstanceOf(ToolInputException.class).hasMessage("unit_code is required");
    }

    @Test
    void nothingAboutARequestCanBeEditedAtApproval() {
        assertThat(executor.editableFields()).isEmpty();
    }

    @Test
    void summarizesTheRequestInBothLanguages() {
        var summary = executor.summary(executor.validate(args()));

        assertThat(summary.en()).isEqualTo(
                "Create a maintenance request for unit A-203 (plumbing, urgent priority): \"Water is leaking from the ceiling\".");
        assertThat(summary.ar()).startsWith("إنشاء طلب صيانة للوحدة A-203 (سباكة، أولوية عاجلة)");
    }
}
