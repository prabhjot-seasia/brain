package com.assurant.brain.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AsyncJobService")
class AsyncJobServiceTest {

    private AsyncJobRepository repository;
    private JobEventPublisher publisher;
    private AsyncJobService service;

    @BeforeEach
    void setup() {
        repository = mock(AsyncJobRepository.class);
        publisher = mock(JobEventPublisher.class);
        service = new AsyncJobService(repository, publisher, new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(repository.saveAndFlush(any(AsyncJobEntity.class))).thenAnswer(inv -> {
            AsyncJobEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    @DisplayName("startOrAttach with no in-flight inserts QUEUED row, attachedToExisting=false")
    void startOrAttachInsertsNew() {
        when(repository.findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty());

        AsyncJob job = service.startOrAttach("FULL_DOC_BUNDLE", "PROJECT", "ce-imei", "ce-imei");

        assertThat(job.status()).isEqualTo(AsyncJobStatus.QUEUED);
        assertThat(job.attachedToExisting()).isFalse();
        verify(repository).saveAndFlush(any(AsyncJobEntity.class));
    }

    @Test
    @DisplayName("startOrAttach when in-flight row exists returns it with attachedToExisting=true")
    void startOrAttachAttachesToExisting() {
        AsyncJobEntity existing = new AsyncJobEntity();
        existing.setId(UUID.randomUUID());
        existing.setJobType("FULL_DOC_BUNDLE");
        existing.setTargetKind("PROJECT");
        existing.setTargetId("ce-imei");
        existing.setStatus(AsyncJobStatus.RUNNING);
        when(repository.findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.of(existing));

        AsyncJob job = service.startOrAttach("FULL_DOC_BUNDLE", "PROJECT", "ce-imei", "ce-imei");

        assertThat(job.attachedToExisting()).isTrue();
        assertThat(job.id()).isEqualTo(existing.getId());
        verify(repository, never()).saveAndFlush(any(AsyncJobEntity.class));
    }

    @Test
    @DisplayName("startOrAttach handles concurrent insert via DataIntegrityViolationException → returns winner row")
    void startOrAttachRaceLost() {
        AsyncJobEntity winner = new AsyncJobEntity();
        winner.setId(UUID.randomUUID());
        winner.setStatus(AsyncJobStatus.QUEUED);
        when(repository.findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any(AsyncJobEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_async_jobs_inflight"));

        AsyncJob job = service.startOrAttach("X", "PROJECT", "p", "p");

        assertThat(job.attachedToExisting()).isTrue();
        assertThat(job.id()).isEqualTo(winner.getId());
    }

    @Test
    @DisplayName("startOrAttach: race-lost insert AND in-flight peer already terminated → retry insert, return new row")
    void startOrAttachRacePeerTerminated() {
        AsyncJobEntity inserted = new AsyncJobEntity();
        inserted.setId(UUID.randomUUID());
        inserted.setStatus(AsyncJobStatus.QUEUED);
        when(repository.findFirstByJobTypeAndTargetKindAndTargetIdAndStatusIn(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AsyncJobEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_async_jobs_inflight"))
                .thenReturn(inserted);

        AsyncJob job = service.startOrAttach("X", "PROJECT", "p", "p");

        assertThat(job.attachedToExisting()).isFalse();
        assertThat(job.id()).isEqualTo(inserted.getId());
    }

    @Test
    @DisplayName("startOrAttach rejects blank jobType / targetKind / targetId")
    void startOrAttachValidates() {
        assertThatThrownBy(() -> service.startOrAttach("", "PROJECT", "p", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startOrAttach("X", null, "p", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startOrAttach("X", "PROJECT", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markRunning sets status RUNNING + startedAt + publishes progress")
    void markRunning() {
        UUID id = UUID.randomUUID();
        AsyncJobEntity entity = entity(id, AsyncJobStatus.QUEUED);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        AsyncJob job = service.markRunning(id, "starting");

        assertThat(job.status()).isEqualTo(AsyncJobStatus.RUNNING);
        assertThat(entity.getStartedAt()).isNotNull();
        verify(publisher).publish(eq(id), any(JobEvent.class));
    }

    @Test
    @DisplayName("updateProgress clamps pct to [0,100] and truncates message")
    void updateProgressClamps() {
        UUID id = UUID.randomUUID();
        AsyncJobEntity entity = entity(id, AsyncJobStatus.RUNNING);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.updateProgress(id, 150, "x".repeat(2000));
        assertThat(entity.getProgressPct()).isEqualTo((short) 100);
        assertThat(entity.getProgressMsg()).hasSize(500);

        service.updateProgress(id, -50, "ok");
        assertThat(entity.getProgressPct()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("updateProgress on terminal job is a no-op")
    void updateProgressTerminalNoOp() {
        UUID id = UUID.randomUUID();
        AsyncJobEntity entity = entity(id, AsyncJobStatus.SUCCEEDED);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.updateProgress(id, 50, "trying");

        verify(repository, never()).saveAndFlush(any(AsyncJobEntity.class));
    }

    @Test
    @DisplayName("markSucceeded sets terminal state, serializes result, publishes + completes")
    void markSucceeded() {
        UUID id = UUID.randomUUID();
        AsyncJobEntity entity = entity(id, AsyncJobStatus.RUNNING);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        AsyncJob job = service.markSucceeded(id, Map.of("documentId", "abc"));

        assertThat(job.status()).isEqualTo(AsyncJobStatus.SUCCEEDED);
        assertThat(entity.getFinishedAt()).isNotNull();
        assertThat(entity.getResult()).contains("abc");
        verify(publisher).publish(eq(id), any(JobEvent.class));
        verify(publisher).complete(id);
    }

    @Test
    @DisplayName("markFailed records errorMessage and completes")
    void markFailed() {
        UUID id = UUID.randomUUID();
        AsyncJobEntity entity = entity(id, AsyncJobStatus.RUNNING);
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        service.markFailed(id, "boom");

        assertThat(entity.getStatus()).isEqualTo(AsyncJobStatus.FAILED);
        assertThat(entity.getErrorMessage()).isEqualTo("boom");
        verify(publisher).complete(id);
    }

    @Test
    @DisplayName("findById delegates and projectIdOf returns the row's projectId")
    void findById() {
        UUID id = UUID.randomUUID();
        AsyncJobEntity entity = entity(id, AsyncJobStatus.RUNNING);
        entity.setProjectId("ce-imei");
        when(repository.findById(id)).thenReturn(Optional.of(entity));

        assertThat(service.findById(id)).isPresent();
        assertThat(service.projectIdOf(id)).contains("ce-imei");
    }

    @Test
    @DisplayName("recentForProject + recentInFlight clamp limits to a sane bounded range")
    void recentBounds() {
        when(repository.findByProjectIdOrderByCreatedAtDesc(anyString(), any())).thenReturn(List.of());
        when(repository.findByStatusInOrderByCreatedAtDesc(anyList(), any())).thenReturn(List.of());

        service.recentForProject("p", 0);
        service.recentForProject("p", 9999);
        service.recentInFlight(0);
        service.recentInFlight(9999);

        verify(repository, times(2)).findByProjectIdOrderByCreatedAtDesc(anyString(), any());
        verify(repository, times(2)).findByStatusInOrderByCreatedAtDesc(anyList(), any());
    }

    private AsyncJobEntity entity(UUID id, AsyncJobStatus status) {
        AsyncJobEntity e = new AsyncJobEntity();
        e.setId(id);
        e.setJobType("X");
        e.setTargetKind("PROJECT");
        e.setTargetId("p");
        e.setStatus(status);
        return e;
    }
}
