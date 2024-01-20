package net.nexus_flow;

import java.util.Objects;

public record MyCommand(String id, String description, int version) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MyCommand myCommand)) return false;
        return version == myCommand.version && Objects.equals(id, myCommand.id) && Objects.equals(description, myCommand.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description, version);
    }

    @Override
    public String toString() {
        return STR."MyCommand{id='\{id}\{'\''}, description='\{description}\{'\''}, version=\{version}\{'}'}";
    }
}