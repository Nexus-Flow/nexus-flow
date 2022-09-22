package com.nexus_flow.core.domain;

import java.io.ByteArrayInputStream;
import java.util.Random;

public final class ByteMother {

    public static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random().nextBytes(bytes);
        return bytes;
    }

    public static ByteArrayInputStream randomAsInputStream(int size) {
        return new ByteArrayInputStream(random(size));
    }
}
