package com.nexus_flow.core.cqrs.domain.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface NexusFlowCommandHandler {
}
