package com.nexus_flow.core.domain;


import net.datafaker.Faker;

public final class MotherCreator {
    private final static Faker faker = new Faker();

    public static Faker random() {
        return faker;
    }
}
