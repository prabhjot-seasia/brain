package com.assurant.brain.intake;

import com.assurant.brain.dao.IntakeRecordRepository;
import com.assurant.brain.domain.IntakeRecord;
import com.assurant.brain.enums.IntakeSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("IntakeResolver")
class IntakeResolverTest {

    private IntakeRecordRepository repository;
    private IntakeResolver resolver;

    @BeforeEach
    void setup() {
        repository = mock(IntakeRecordRepository.class);
        resolver = new IntakeResolver(repository);
    }

    @Test
    @DisplayName("returns extracted text for a valid intake id")
    void resolvesValidId() {
        UUID id = UUID.randomUUID();
        IntakeRecord record = new IntakeRecord();
        record.setId(id);
        record.setSourceType(IntakeSourceType.JIRA_TICKET);
        record.setExtractedText("Requirement body here");
        when(repository.findById(id)).thenReturn(Optional.of(record));

        assertThat(resolver.resolve(id)).isEqualTo("Requirement body here");
    }

    @Test
    @DisplayName("throws when intake id does not exist")
    void throwsOnMissingId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IntakeRecord not found");
    }

    @Test
    @DisplayName("throws when record has no extracted text")
    void throwsOnEmptyText() {
        UUID id = UUID.randomUUID();
        IntakeRecord record = new IntakeRecord();
        record.setId(id);
        record.setSourceType(IntakeSourceType.ADHOC_TEXT);
        record.setExtractedText("");
        when(repository.findById(id)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> resolver.resolve(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no extracted text");
    }
}
