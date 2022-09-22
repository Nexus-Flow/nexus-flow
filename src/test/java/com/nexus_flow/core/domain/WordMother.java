package com.nexus_flow.core.domain;

public final class WordMother {
    public static String random() {
        return MotherCreator.random().lorem().word();
    }
}
