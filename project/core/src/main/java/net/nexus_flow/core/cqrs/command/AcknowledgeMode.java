package net.nexus_flow.core.cqrs.command;

public enum AcknowledgeMode {
    NONE,
    MANUAL,
    AUTO;

    AcknowledgeMode() {
    }

    public boolean isTransactionAllowed() {
        return this == AUTO || this == MANUAL;
    }

    public boolean isAutoAck() {
        return this == NONE;
    }

    public boolean isManual() {
        return this == MANUAL;
    }
}
