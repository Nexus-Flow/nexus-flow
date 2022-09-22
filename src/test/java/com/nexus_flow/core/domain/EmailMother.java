package com.nexus_flow.core.domain;

public final class EmailMother {
    public static String random() {
        return MotherCreator.random().internet().emailAddress();
    }
}
