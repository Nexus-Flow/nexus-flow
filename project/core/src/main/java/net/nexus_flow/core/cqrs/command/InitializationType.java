package net.nexus_flow.core.cqrs.command;

public enum InitializationType {
    EAGER,
    LAZY;

    public boolean isEager() {
        return this.equals(EAGER);
    }

    public boolean isLazy() {
        return this.equals(LAZY);
    }
}