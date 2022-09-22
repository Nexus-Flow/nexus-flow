package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggerId;

import java.util.List;

public interface SagaRepository {

    void saveSagaMember(SagaMember sagaMember);

    List<SagaMember> searchSaga(SagaTriggerId triggerId);

    List<SagaMemberClass> searchArrivedMembers(SagaTriggerId triggerId);

    void deleteSaga(SagaTriggerId triggerId);

}
