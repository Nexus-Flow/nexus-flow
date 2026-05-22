package net.nexus_flow.core.runtime.dispatch;

import java.io.Serial;
import net.nexus_flow.core.runtime.result.FlowError;

/** Shared test fixtures for dispatch test suite. */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
final class DispatchTestFixtures {

    private DispatchTestFixtures() {
    }

    /** Domain exception that opts into the error model's no-wrap path. */
    static final class InvalidSku extends RuntimeException implements FlowError.Domain {
        @Serial
        private static final long serialVersionUID = 1L;

        InvalidSku(String m) {
            super(m);
        }
    }
}
