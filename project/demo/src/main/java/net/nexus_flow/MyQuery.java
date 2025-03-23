package net.nexus_flow;

import java.util.Objects;

public record MyQuery(String id) {

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MyQuery) obj;
        return Objects.equals(this.id, that.id);
    }

    @Override
    public String toString() {
        return String.format("MyQuery[id=%s]", id);
    }

}