package net.nexus_flow.core.runtime;

/**
 * Internal utility for attaching suppressed exceptions without losing the primary cause.
 *
 * <p>Used throughout the runtime whenever a secondary exception should travel alongside the primary
 * (e.g. the {@link java.util.concurrent.ExecutionException} wrapper kept alongside the unwrapped
 * cause, or concurrent-failure losers attached to the winner under {@link ErrorPolicy.FailFast}).
 */
public final class ThrowableUtils {

    private ThrowableUtils() {
    }

    /**
     * Attach {@code suppressed} to {@code thrown} via {@link Throwable#addSuppressed(Throwable)} and
     * return {@code thrown}.
     *
     * <p>The call is a no-op when {@code suppressed} is {@code null} or the same object as {@code
     * thrown} (guards against {@link IllegalArgumentException} from {@code addSuppressed}).
     *
     * @param <T>        throwable type, inferred from {@code thrown}
     * @param thrown     the primary exception that will be thrown to the caller; never {@code null}
     * @param suppressed the secondary exception to attach; may be {@code null}
     * @return {@code thrown}, with {@code suppressed} attached if applicable
     */
    public static <T extends Throwable> T withSuppressed(T thrown, Throwable suppressed) {
        if (suppressed != null && suppressed != thrown) {
            thrown.addSuppressed(suppressed);
        }
        return thrown;
    }
}
