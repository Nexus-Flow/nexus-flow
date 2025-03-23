package net.nexus_flow;

import net.nexus_flow.core.ddd.Aggregate;

import java.io.Serial;
import java.util.Objects;

public class TestAggregate extends Aggregate {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String id;
    private String description;
    private int version;

    public TestAggregate(String id, String description, int version) {
        this.id = id;
        this.description = description;
        this.version = version;
    }

    public void update(String description, int version) {
        this.description = description;
        this.version = version;
        recordEvent(new UpdateTestDomainEvent(this.id, this.description, this.version));
    }

    public String getId() {
        return id;
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
        if (!(o instanceof TestAggregate that)) return false;
        return getVersion() == that.getVersion() && Objects.equals(getId(), that.getId()) && Objects.equals(getDescription(), that.getDescription());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getDescription(), getVersion());
    }

    @Override
    public String toString() {
        return String.format("TestAggregate{id='%s', description='%s', version=%s}", id, description, version);
    }
}