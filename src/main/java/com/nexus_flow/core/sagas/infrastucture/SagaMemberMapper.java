package com.nexus_flow.core.sagas.infrastucture;


import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.SagaDomainEvent;
import com.nexus_flow.core.sagas.domain.SagaMember;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.CommandSagaMember;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.EventNotTriggerSagaMember;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.EventTriggerSagaMember;
import com.nexus_flow.core.sagas.domain.value_objects.saga_member.SagaMemberClass;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Component
public class SagaMemberMapper {

    private final ObjectMapper mapper;

    public SagaMemberMapper() {
        mapper = new ObjectMapper();
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator
                .builder()
                .allowIfSubType(EventTriggerSagaMember.class)
                .allowIfSubType(CommandSagaMember.class)
                .allowIfSubType(EventNotTriggerSagaMember.class)
                .allowIfBaseType(SagaCommand.class)
                .allowIfBaseType(SagaDomainEvent.class)
                .allowIfBaseType(DomainEvent.class)
                .allowIfBaseType("com.nexus_flow.")
                .allowIfBaseType(Set.class)
                .allowIfBaseType(Map.class)
                .allowIfBaseType(List.class)
                .build();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.activateDefaultTyping(ptv);
        mapper.findAndRegisterModules();
    }

    @SneakyThrows
    public SagaMember toDomain(SagaMemberEntity source) {
        if (source == null) return null;
        return mapper.readValue(source.getAggregate(), SagaMember.class);
    }

    public List<SagaMember> toDomain(List<SagaMemberEntity> source) {
        if (source == null) return new ArrayList<>();
        return source.stream().map(this::toDomain).collect(Collectors.toList());
    }

    @SneakyThrows
    public SagaMemberClass toDomainArrivedMember(SagaArrivedMemberProjection memberProjection) {
        if (memberProjection == null) return null;
        return new SagaMemberClass(Class.forName(memberProjection.getSagaMemberClass()));
    }

    public List<SagaMemberClass> toDomainArrivedMembers(List<SagaArrivedMemberProjection> memberProjections) {
        if (memberProjections == null) return new ArrayList<>();
        return memberProjections.stream().map(this::toDomainArrivedMember).collect(Collectors.toList());
    }


    @SneakyThrows
    public SagaMemberEntity toEntity(SagaMember source) {

        SagaMemberEntity sagaEntity = new SagaMemberEntity();

        if (source != null) {
            sagaEntity.setSagaMemberId(sourceSagaMemberIdValue(source));
            sagaEntity.setSagaMemberClass(sourceSagaMemberClassValue(source));
            sagaEntity.setSagaTriggerId(sourceSagaTriggerIdValue(source));
            sagaEntity.setSagaName(sourceSagaNameValue(source));
            sagaEntity.setSagaTriggeredOn(sourceSagaTriggeredOnValue(source));
            sagaEntity.setAggregate(mapper.writeValueAsString(source));
        }

        return sagaEntity;
    }

    private String sourceSagaMemberIdValue(SagaMember sagaMember) {
        return Optional.ofNullable(sagaMember)
                .map(saga1 -> saga1.getSagaMemberId().getValue())
                .orElse(null);
    }

    private String sourceSagaMemberClassValue(SagaMember sagaMember) {
        return Optional.ofNullable(sagaMember)
                .map(saga1 -> saga1.getSagaMemberClass().getValue().getName())
                .orElse(null);
    }

    private String sourceSagaTriggerIdValue(SagaMember sagaMember) {
        return Optional.ofNullable(sagaMember)
                .map(saga1 -> saga1.getSagaTriggerId().getValue())
                .orElse(null);
    }

    private String sourceSagaNameValue(SagaMember sagaMember) {
        return Optional.ofNullable(sagaMember)
                .map(saga1 -> saga1.getSagaName().getValue())
                .orElse(null);
    }

    private ZonedDateTime sourceSagaTriggeredOnValue(SagaMember sagaMember) {
        return Optional.ofNullable(sagaMember)
                .map(saga1 -> saga1.getSagaTriggeredOn().getValue())
                .orElse(null);
    }

}
