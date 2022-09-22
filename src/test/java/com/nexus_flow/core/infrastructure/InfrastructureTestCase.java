package com.nexus_flow.core.infrastructure;

import com.nexus_flow.core.configurations.tests.NexusFlowActiveProfilesResolver;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

@Tag("integration")
@ActiveProfiles(resolver = NexusFlowActiveProfilesResolver.class)
public abstract class InfrastructureTestCase {
    private final int MAX_ATTEMPTS                   = 3;
    private final int MILLIS_TO_WAIT_BETWEEN_RETRIES = 300;

    protected void eventually(Runnable assertion) throws Exception {
        int     attempts = 0;
        boolean allOk    = false;

        while (attempts < MAX_ATTEMPTS && !allOk) {
            try {
                assertion.run();

                allOk = true;
            } catch (Throwable error) {
                attempts++;

                if (attempts > MAX_ATTEMPTS) {
                    throw new Exception(
                            String.format("Could not assert after some retries. Last error: %s", error.getMessage())
                    );
                }

                Thread.sleep(MILLIS_TO_WAIT_BETWEEN_RETRIES);
            }
        }
    }
}
