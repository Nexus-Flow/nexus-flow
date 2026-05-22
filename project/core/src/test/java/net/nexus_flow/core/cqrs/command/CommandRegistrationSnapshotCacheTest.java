package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.Serial;
import java.io.Serializable;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins the read-side caching of {@link CommandRegistrationSnapshot} introduced to remove the
 * per-call {@code Set.copyOf} × 2 allocation. The cache MUST:
 *
 * <ul>
 * <li>return the same instance across repeated reads when the registry has not changed;
 * <li>invalidate on every mutation (register / unregister of either role);
 * <li>recompute on the first read after invalidation.
 * </ul>
 *
 * <p>The test is allocation-aware on purpose: identity equality is the load-bearing assertion.
 * Two value-equal snapshots passing only {@code equals()} would still mean the registry is
 * allocating per call.
 */
class CommandRegistrationSnapshotCacheTest {

    record CmdA(String x) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    record CmdB(String x) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    static final class HandlerA extends AbstractNoReturnCommandHandler<CmdA> {
        @Override
        public void handle(CmdA cmd) {
        }
    }

    static final class HandlerB extends AbstractReturnCommandHandler<CmdB, String> {
        @Override
        public String handle(CmdB cmd) {
            return "ok";
        }
    }

    @Test
    void repeatedReads_returnSameInstance_whileRegistryIsStable() {
        try (FlowRuntime runtime = FlowRuntime.builder().handlers(new HandlerA(), new HandlerB()).build()) {
            CommandRegistrationSnapshot first  = runtime.commands().registrationSnapshot();
            CommandRegistrationSnapshot second = runtime.commands().registrationSnapshot();
            for (int i = 0; i < 100; i++) {
                CommandRegistrationSnapshot s = runtime.commands().registrationSnapshot();
                assertSame(first, s,
                           "cached snapshot MUST return the same instance across reads when the"
                                   + " registry has not changed (iter " + i + ")");
            }
            assertSame(first, second);
        }
    }

    @Test
    void mutation_invalidatesCache_andNextReadRebuildsFresh() {
        try (FlowRuntime runtime = FlowRuntime.builder().handler(new HandlerA()).build()) {
            CommandRegistrationSnapshot before = runtime.commands().registrationSnapshot();
            // Register a second handler. The cache MUST rebuild on the next read.
            runtime.commands().register(new HandlerB());
            CommandRegistrationSnapshot after = runtime.commands().registrationSnapshot();
            assertNotSame(before, after,
                          "register MUST invalidate the cache; next read returns a fresh snapshot");
        }
    }

    @Test
    void unregister_invalidatesCache() {
        HandlerA a = new HandlerA();
        try (FlowRuntime runtime = FlowRuntime.builder().handler(a).build()) {
            CommandRegistrationSnapshot before = runtime.commands().registrationSnapshot();
            runtime.commands().unregister(a);
            CommandRegistrationSnapshot after = runtime.commands().registrationSnapshot();
            assertNotSame(before, after,
                          "unregister MUST invalidate the cache; next read returns a fresh snapshot");
        }
    }
}
