package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins the configurable safety cap on per-handler {@code concurrencyLevel}: a handler that returns
 * a pathological value (e.g. {@link Integer#MAX_VALUE}) is clamped at construction time to the cap
 * sourced from {@link ConcurrencySettings} (default {@link ConcurrencySettings#defaults()}), a
 * {@code WARNING} is logged naming the requested value and the cap, and the handler continues to
 * dispatch normally. Handlers that need a higher cap set it explicitly via {@link
 * CommandSettings.Builder#concurrency(ConcurrencySettings)}.
 *
 * <p>Without the cap, three sinks would explode at registration time:
 *
 * <ul>
 * <li>The {@link java.util.concurrent.PriorityBlockingQueue} initial-capacity allocation — {@code
 *       Math.min(concurrencyLevel, 1) → Integer.MAX_VALUE} attempts to allocate an {@code Object[]}
 * sized at {@code Integer.MAX_VALUE}: instant OOM.
 * <li>The {@link java.util.concurrent.Semaphore} permit count.
 * <li>The {@code initializeExecution} loop submitting one drainer virtual thread per unit of
 * concurrency — {@code Integer.MAX_VALUE} VT submissions to the shared runtime executor,
 * killing the runtime from a single misconfigured handler.
 * </ul>
 *
 * <p>The clamp lives at constructor entry so all three sinks are protected by a single check; the
 * cap is sourced from {@link CommandSettings#concurrency()} before any sink is touched.
 */
class DefaultCommandHandlerExecutorConcurrencyClampTest {

    record Ping(int n) {
    }

    /** JUL handler that captures {@link LogRecord} instances for assertion. */
    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            /* no-op */
        }

        @Override
        public void close() {
            /* no-op */
        }
    }

    @Test
    void absurdConcurrencyLevel_isClampedToDefault_andLogged_andHandlerStillDispatches() throws Exception {
        // Attach a JUL captor on the executor's logger. System.Logger routes through JUL by default
        // in the JDK, so the WARNING emitted by DefaultCommandHandlerExecutor lands here.
        Logger           jul        = Logger.getLogger(DefaultCommandHandlerExecutor.class.getName());
        Level            priorLevel = jul.getLevel();
        CapturingHandler captor     = new CapturingHandler();
        jul.setLevel(Level.WARNING);
        jul.addHandler(captor);

        AbstractNoReturnCommandHandler<Ping> absurd =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Ping cmd) {
                        /* no-op — we only care about registration-time clamping */
                    }

                    @Override
                    public int getConcurrencyLevel() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public InitializationType getInitializationType() {
                        return InitializationType.EAGER;
                    }
                };

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(absurd);
            try {
                DefaultCommandHandlerExecutor<?, ?, ?> executor = locateExecutorFor(runtime, absurd);

                // (a) The field MUST be clamped to the default cap, not Integer.MAX_VALUE.
                Field clField = DefaultCommandHandlerExecutor.class.getDeclaredField("concurrencyLevel");
                clField.setAccessible(true);
                int actual = clField.getInt(executor);
                assertEquals(
                             ConcurrencySettings.defaults().maxLevel(),
                             actual,
                             "concurrencyLevel MUST be clamped to ConcurrencySettings.DEFAULTS.maxLevel()");

                // (b) A WARNING must have been emitted naming the requested value AND the cap.
                boolean foundWarning =
                        captor.records.stream()
                                .filter(r -> r.getLevel() == Level.WARNING)
                                .map(LogRecord::getMessage)
                                .anyMatch(
                                          msg -> msg.contains(String.valueOf(Integer.MAX_VALUE)) && msg.contains(
                                                                                                                 String.valueOf(ConcurrencySettings
                                                                                                                         .defaults()
                                                                                                                         .maxLevel())));
                assertTrue(
                           foundWarning,
                           "WARNING log MUST mention the requested value and the cap; captured records: "
                                   + captor.records.stream().map(LogRecord::getMessage).toList());

                // (c) Dispatch still works after the clamp — clamp is non-fatal.
                runtime.commands().dispatch(Command.<Ping>builder().body(new Ping(1)).build());
            } finally {
                runtime.commands().unregister(absurd);
            }
        } finally {
            jul.removeHandler(captor);
            jul.setLevel(priorLevel);
        }
    }

    @Test
    void customConcurrencyCapInSettings_isRespectedInsteadOfDefault() throws Exception {
        int              customCap  = 512;
        Logger           jul        = Logger.getLogger(DefaultCommandHandlerExecutor.class.getName());
        Level            priorLevel = jul.getLevel();
        CapturingHandler captor     = new CapturingHandler();
        jul.setLevel(Level.WARNING);
        jul.addHandler(captor);

        CommandSettings settings =
                CommandSettings.builder().concurrency(new ConcurrencySettings(customCap)).build();

        AbstractNoReturnCommandHandler<Ping> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Ping cmd) {
                        /* no-op */
                    }

                    @Override
                    public int getConcurrencyLevel() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public CommandSettings getCommandSettings() {
                        return settings;
                    }
                };

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(handler);
            try {
                DefaultCommandHandlerExecutor<?, ?, ?> executor = locateExecutorFor(runtime, handler);

                Field clField = DefaultCommandHandlerExecutor.class.getDeclaredField("concurrencyLevel");
                clField.setAccessible(true);
                int actual = clField.getInt(executor);
                assertEquals(
                             customCap,
                             actual,
                             "concurrencyLevel MUST be clamped to the custom ConcurrencySettings.maxLevel()");

                boolean foundWarning =
                        captor.records.stream()
                                .filter(r -> r.getLevel() == Level.WARNING)
                                .map(LogRecord::getMessage)
                                .anyMatch(
                                          msg -> msg.contains(String.valueOf(Integer.MAX_VALUE)) && msg.contains(String.valueOf(
                                                                                                                                customCap)));
                assertTrue(
                           foundWarning,
                           "WARNING log MUST mention the requested value and the custom cap; records: "
                                   + captor.records.stream().map(LogRecord::getMessage).toList());
            } finally {
                runtime.commands().unregister(handler);
            }
        } finally {
            jul.removeHandler(captor);
            jul.setLevel(priorLevel);
        }
    }

    /**
     * Reflection helper — mirrors {@code CooperativeCancellationInPollLoopTest}'s pattern for
     * reaching the package-private executor instance through the {@code CommandBus} → {@code
     * DefaultCommandConsumerRegistry} → {@code CommandExecutorEntry} chain.
     */
    private static DefaultCommandHandlerExecutor<?, ?, ?> locateExecutorFor(
            FlowRuntime runtime, AbstractNoReturnCommandHandler<?> handler) throws Exception {
        Object bus           = runtime.commands();
        Field  registryField = bus.getClass().getDeclaredField("consumerRegistry");
        registryField.setAccessible(true);
        Object registry = registryField.get(bus);

        Field mapField = registry.getClass().getDeclaredField("executorMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Object, Object> map = (Map<Object, Object>) mapField.get(registry);

        for (Object value : map.values()) {
            Object candidate = value;
            try {
                Method executorMethod = value.getClass().getDeclaredMethod("executor");
                executorMethod.setAccessible(true);
                candidate = executorMethod.invoke(value);
            } catch (NoSuchMethodException _) {
                // bare executor — should not happen post executor-entry consolidation
            }
            Field outerHandlerField = findFieldInHierarchy(candidate.getClass(), "outerHandler");
            if (outerHandlerField == null) {
                continue;
            }
            outerHandlerField.setAccessible(true);
            if (outerHandlerField.get(candidate) == handler) {
                return (DefaultCommandHandlerExecutor<?, ?, ?>) candidate;
            }
        }
        fail("could not locate DefaultCommandHandlerExecutor for handler " + handler);
        throw new AssertionError("unreachable — fail() above terminates the test");
    }

    private static Field findFieldInHierarchy(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                /* keep walking */
            }
        }
        return null;
    }
}
