package net.nexus_flow;

import java.util.Objects;

public record MyCommand(String id, String description, int version) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MyCommand(String id1, String description1, int version1))) return false;
        return version == version1 && Objects.equals(id, id1) && Objects.equals(description, description1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description, version);
    }

    @Override
    public String toString() {
        return String.format("MyCommand{id='%s', description='%s', version=%s}", id, description, version);
    }
}