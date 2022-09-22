package com.nexus_flow.core.domain;

public final class LongMother {

    public static Long random() {
        return MotherCreator.random().number().randomNumber();
    }

    public static Long randomBetween(long min, long max) {
        return MotherCreator.random().number().numberBetween(min, max);
    }
}
