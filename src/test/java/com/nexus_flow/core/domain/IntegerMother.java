package com.nexus_flow.core.domain;

public final class IntegerMother {

    /**
     * Returns a random number from 0-9 (both inclusive)
     */
    public static Integer random() {
        return MotherCreator.random().number().randomDigit();
    }

    public static Integer randomBetween(int min, int max) {
        return MotherCreator.random().number().numberBetween(min, max);
    }
}
