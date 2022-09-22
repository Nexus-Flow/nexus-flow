package com.nexus_flow.core.sagas.infrastucture;

import com.nexus_flow.core.ddd.annotations.NexusFlowRepository;
import com.nexus_flow.core.sagas.domain.SagaMember;
import com.nexus_flow.core.sagas.domain.SagaRepository;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaTriggerId;

import java.util.List;

@NexusFlowRepository
public class PostgresSagaRepository implements SagaRepository {

    private final SagaMemberMapper           sagaMapper;
    private final SpringDataJpaSagaInterface springDataJpaSagaInterface;

    public PostgresSagaRepository(SpringDataJpaSagaInterface springDataJpaSagaInterface, SagaMemberMapper sagaMapper) {
        this.springDataJpaSagaInterface = springDataJpaSagaInterface;
        this.sagaMapper                 = sagaMapper;
    }


    @Override
    public void saveSagaMember(SagaMember sagaMember) {
        springDataJpaSagaInterface.save(sagaMapper.toEntity(sagaMember));
        flush();
    }

    @Override
    public List<SagaMember> searchSaga(SagaTriggerId triggerId) {
        List<SagaMember> sagaMembers = sagaMapper.toDomain(springDataJpaSagaInterface.findAllBySagaTriggerId(triggerId.getValue()));
        flush();
        return sagaMembers;
    }

    @Override
    public List<SagaMemberClass> searchArrivedMembers(SagaTriggerId triggerId) {
        List<SagaMemberClass> arrivedMembers = sagaMapper
                .toDomainArrivedMembers(springDataJpaSagaInterface.findBySagaTriggerId(triggerId.getValue()));
        flush();
        return arrivedMembers;
    }

    @Override
    public void deleteSaga(SagaTriggerId triggerId) {
        springDataJpaSagaInterface.deleteBySagaTriggerId(triggerId.getValue());
    }

    public void flush() {
        springDataJpaSagaInterface.flush();
    }

}
