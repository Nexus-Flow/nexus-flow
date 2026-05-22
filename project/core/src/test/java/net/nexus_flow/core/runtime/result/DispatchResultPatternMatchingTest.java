package net.nexus_flow.core.runtime.result;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pattern-matching exhaustiveness for {@link DispatchResult} and {@link CommandResult}.
 *
 * <p>Each {@code switch} is written without a {@code default} branch on purpose: when the sealed
 * hierarchy grows the compiler must surface the missing case at this exact location, instead of
 * silently swallowing it under {@code default}.
 */
class DispatchResultPatternMatchingTest {

    @Test
    void dispatchResult_success_matchesSuccessVariant() {
        DispatchResult<String> result = DispatchResult.success("ok");
        String                 tag    = describe(result);
        assertEquals("success:ok", tag);
        assertTrue(result.isSuccess());
    }

    @Test
    void dispatchResult_failure_matchesFailureVariant() {
        RuntimeException       cause  = new RuntimeException("boom");
        DispatchResult<String> result = DispatchResult.failure(cause);
        String                 tag    = describe(result);
        assertEquals("failure:boom", tag);
        assertTrue(result.isFailure());
    }

    @Test
    void dispatchResult_partialFailure_matchesPartialVariant() {
        RuntimeException       a      = new RuntimeException("a");
        RuntimeException       b      = new RuntimeException("b");
        DispatchResult<String> result = DispatchResult.partial("partial", List.of(a, b));
        String                 tag    = describe(result);
        assertEquals("partial:partial:2", tag);
        assertTrue(result.isPartialFailure());
    }

    @Test
    void commandResult_success_matchesSuccessVariant() {
        CommandResult<Integer> r   = CommandResult.success(42);
        String                 tag =
                switch (r) {
                                               case CommandResult.Success<Integer> s -> "success:" + s.value() + ":" + s.events().size();
                                               case CommandResult.Failure<Integer> f -> "failure:" + f.cause().getMessage();
                                           };
        assertEquals("success:42:0", tag);
    }

    @Test
    void commandResult_failure_matchesFailureVariant() {
        Exception              cause = new IllegalStateException("nope");
        CommandResult<Integer> r     = CommandResult.failure(cause);
        String                 tag   =
                switch (r) {
                                                 case CommandResult.Success<Integer> s -> "success:" + s.value();
                                                 case CommandResult.Failure<Integer> f -> "failure:" + f.cause().getMessage();
                                             };
        assertEquals("failure:nope", tag);
        assertSame(cause, ((CommandResult.Failure<Integer>) r).cause());
    }

    /**
     * Helper kept outside individual tests to make the exhaustiveness intent obvious: the {@code
     * switch} has no {@code default} branch.
     */
    private static <T> String describe(DispatchResult<T> result) {
        return switch (result) {
            case DispatchResult.Success<T> s        -> "success:" + s.value();
            case DispatchResult.Failure<T> f        -> "failure:" + f.cause().getMessage();
            case DispatchResult.PartialFailure<T> p -> "partial:" + p.value() + ":" + p.failures().size();
            case DispatchResult.Accepted<T> a       -> "accepted:" + a.messageId();
        };
    }
}
