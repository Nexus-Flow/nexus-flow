package net.nexus_flow.core.cqrs.reflection;

import java.lang.reflect.Type;

public interface TypeReferenceResolver {
    Type resolve(Type type);
}
