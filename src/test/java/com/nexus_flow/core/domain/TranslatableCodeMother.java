package com.nexus_flow.core.domain;

import com.nexus_flow.core.ddd.value_objects.TranslatableCode;

public final class TranslatableCodeMother {

    public static TranslatableCode create(String value) {
        return new TranslatableCode(value);
    }

    public static TranslatableCode random() {
        return create(WordMother.random());
    }

}
