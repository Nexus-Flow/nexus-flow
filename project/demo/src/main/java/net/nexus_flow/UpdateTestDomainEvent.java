package net.nexus_flow;

import net.nexus_flow.core.ddd.AbstractDomainEvent;

import java.util.Objects;

public class UpdateTestDomainEvent extends AbstractDomainEvent {

    private final String description;
    private final int version;

    protected UpdateTestDomainEvent(String aggregateId,
                                    String description,
                                    int version) {
        super(aggregateId);
        this.description = description;
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public int getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UpdateTestDomainEvent that)) return false;
        return getVersion() == that.getVersion() && Objects.equals(getDescription(), that.getDescription());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getDescription(), getVersion());
    }

    @Override
    public String toString() {
        return STR."UpdateTestDomainEvent{description='\{description}\{'\''}, version=\{version}\{'}'}";
    }
}