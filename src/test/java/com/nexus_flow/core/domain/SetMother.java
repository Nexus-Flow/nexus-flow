package com.nexus_flow.core.domain;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public final class SetMother {
    public static <T> Set<T> create(Integer size, Supplier<T> creator) {
        Set<T> set = new HashSet<>();

        for (int i = 0; i < size; i++) {
            set.add(creator.get());
        }

        return set;
    }

    public static <T> Set<T> random(Supplier<T> creator) {
        return create(IntegerMother.randomBetween(1, 5), creator);
    }

    public static <T> Set<T> one(T element) {
        return Collections.singleton(element);
    }
}
