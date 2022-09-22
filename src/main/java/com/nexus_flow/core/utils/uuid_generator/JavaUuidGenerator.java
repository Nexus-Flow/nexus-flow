package com.nexus_flow.core.utils.uuid_generator;


import com.nexus_flow.core.ddd.annotations.NexusFlowService;

import java.util.UUID;

@NexusFlowService
public final class JavaUuidGenerator implements UuidGenerator {
    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
