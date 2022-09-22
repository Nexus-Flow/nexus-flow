package com.nexus_flow.core.domain;

public final class PersonMother {
    public static String random() {
        return MotherCreator.random().name().fullName();
    }
}
