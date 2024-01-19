package org.nexusflow.core.cqrs.command;

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