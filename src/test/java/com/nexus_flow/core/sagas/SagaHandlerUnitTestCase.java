package com.nexus_flow.core.sagas;

import com.nexus_flow.core.sagas.domain.SagaMember;
import com.nexus_flow.core.sagas.domain.SagaRepository;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggerId;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public abstract class SagaHandlerUnitTestCase {

    protected SagaRepository repository;

    @BeforeEach
    protected void setUp() {
        repository = mock(SagaRepository.class);
    }

    protected void shouldHaveSaved(SagaMember sagaMember) {
        verify(repository).saveSagaMember(sagaMember);
    }

    protected void shouldHaveSavedNothing() {
        verify(repository, never()).saveSagaMember(any(SagaMember.class));
    }

    protected void repositoryIsGoingToReturn(SagaMember sagaMember) {
        List<SagaMember> sagaMembers = new ArrayList<>();
        sagaMembers.add(sagaMember);
        repositoryIsGoingToReturn(sagaMembers);
    }

    protected void repositoryIsGoingToReturn(List<SagaMember> sagaMembers) {
        doReturn(sagaMembers).when(repository).searchSaga(any(SagaTriggerId.class));
    }

    protected void repositoryIsGoingToReturnProcessedMembers(SagaMemberClass arrivedMember) {
        List<SagaMemberClass> arrivedMembers = new ArrayList<>();
        arrivedMembers.add(arrivedMember);
        repositoryIsGoingToReturnProcessedMembers(arrivedMembers);
    }

    protected void repositoryIsGoingToReturnProcessedMembers(List<SagaMemberClass> arrivedMembers) {
        doReturn(arrivedMembers).when(repository).searchArrivedMembers(any(SagaTriggerId.class));
    }

    protected void repositoryIsGoingToReturnNoMember() {
        doReturn(new ArrayList<SagaMember>()).when(repository).searchSaga(any(SagaTriggerId.class));
    }

    protected void repositoryIsGoingToReturnNoProcessedMembers() {
        doReturn(new ArrayList<SagaMemberClass>()).when(repository).searchSaga(any(SagaTriggerId.class));
    }
}
