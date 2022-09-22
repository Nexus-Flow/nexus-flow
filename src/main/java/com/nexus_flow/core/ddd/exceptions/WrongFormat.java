package com.nexus_flow.core.ddd.exceptions;

import com.nexus_flow.core.ddd.Utils;

public final class WrongFormat extends DomainError {
    public WrongFormat(String msg) {
        super("wrong_format", msg);
    }

    public WrongFormat(Class<?> aClass) {
        super("wrong_format", Utils.toSnake(aClass.getSimpleName()) + " can't be null");
    }
}
