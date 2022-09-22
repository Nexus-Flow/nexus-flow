package com.nexus_flow.core.sagas.infrastucture;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


public interface SpringDataJpaSagaInterface extends Repository<SagaMemberEntity, String> {

    //    @Transactional
    void save(SagaMemberEntity sagaEntity);

    //    @Transactional
    List<SagaMemberEntity> findAllBySagaTriggerId(String sagaId);

    @Transactional
    void deleteBySagaTriggerId(String sagaId);

    //    @Transactional
    List<SagaArrivedMemberProjection> findBySagaTriggerId(String sagaId);

    void flush();
}

