package com.nexus_flow.core.domain;

public final class SentenceMother {
    public static String random(int words) {
        return MotherCreator.random().lorem().sentence(words, 0);
    }
}
