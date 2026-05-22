package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import net.nexus_flow.core.runtime.ExecutionMode;
import org.junit.jupiter.api.Test;

/**
 * Validates the {@link CommandSettings#executionMode()} contract: the field is nullable, the getter
 * exposes an {@link Optional} view, and the {@link CommandSettings.Builder} is the only public
 * setter.
 */
class CommandSettingsExecutionModeTest {

    @Test
    void executionMode_defaults_toEmpty() {
        CommandSettings settings = new CommandSettings();
        assertEquals(
                     Optional.empty(),
                     settings.executionMode(),
                     "An un-customised CommandSettings must NOT carry an executionMode override; "
                             + "the resolver falls back to the saga/runtime precedence chain.");
    }

    @Test
    void builder_default_isEmpty() {
        CommandSettings built = CommandSettings.builder().build();
        assertEquals(
                     Optional.empty(),
                     built.executionMode(),
                     "CommandSettings.builder().build() must respect the documented null default.");
    }

    @Test
    void builder_executionMode_synchronous_isHonoured() {
        CommandSettings built =
                CommandSettings.builder().executionMode(ExecutionMode.synchronous()).build();
        assertTrue(
                   built.executionMode().isPresent(),
                   "Builder.executionMode(Synchronous) must produce a non-empty Optional.");
        assertSame(ExecutionMode.synchronous(), built.executionMode().orElseThrow());
    }

    @Test
    void builder_executionMode_asynchronousInMemory_isHonoured() {
        CommandSettings built =
                CommandSettings.builder().executionMode(ExecutionMode.asynchronousInMemory()).build();
        assertSame(ExecutionMode.asynchronousInMemory(), built.executionMode().orElseThrow());
    }

    @Test
    void builder_executionMode_null_clearsOverride() {
        CommandSettings built =
                CommandSettings.builder()
                        .executionMode(ExecutionMode.asynchronousInMemory())
                        .executionMode(null)
                        .build();
        assertEquals(
                     Optional.empty(),
                     built.executionMode(),
                     "Passing null to Builder.executionMode must clear a previously-set override.");
    }
}
