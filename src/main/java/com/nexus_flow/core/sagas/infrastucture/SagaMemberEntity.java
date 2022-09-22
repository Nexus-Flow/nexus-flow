package com.nexus_flow.core.sagas.infrastucture;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;

@Data
@Entity
@Table(name = "saga_members")
@NoArgsConstructor
public final class SagaMemberEntity {

    @Id
    private String sagaMemberId;

    @Column
    private String sagaMemberClass;

    @Column
    private String sagaTriggerId;

    @Column
    private String sagaName;

    @Column
    private ZonedDateTime sagaTriggeredOn;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String aggregate;

}
