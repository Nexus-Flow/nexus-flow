package com.nexus_flow.core.messaging.infrastructure.postgres;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

@Data
@Entity
@Table(name = "domain_events")
@NoArgsConstructor
public final class DomainEventEntity {

    @Id
    @Column(name = "eventId", length = 36)
    private String eventId;

    @Column(name = "aggregateId")
    private String aggregateId;

    @Column
    private String name;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String body;

    @Column
    private java.sql.Timestamp occurredOn;

    @Column
    private Integer attemptsToPublish;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DomainEventEntity that = (DomainEventEntity) o;
        return Objects.equals(eventId, that.eventId) &&
                Objects.equals(aggregateId, that.aggregateId) &&
                Objects.equals(name, that.name) &&
                Objects.equals(body, that.body) &&
                Objects.equals(occurredOn, that.occurredOn) &&
                Objects.equals(attemptsToPublish, that.attemptsToPublish);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, aggregateId, name, body, occurredOn, attemptsToPublish);
    }
}
