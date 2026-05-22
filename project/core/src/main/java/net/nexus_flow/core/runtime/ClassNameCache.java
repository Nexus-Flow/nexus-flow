package net.nexus_flow.core.runtime;

/**
 * Process-wide {@link ClassValue}-backed cache of {@code Class.getName()} results.
 *
 * <p>Walking class metadata to compute {@code getName()} costs ~25–35 ns per call on x86;
 * a {@link ClassValue} stores the first computed value per {@link Class} reference and
 * returns it on subsequent lookups with a lock-free single-field read. Hot for JFR field
 * assignment, observability tagging, and any dispatch path that reports the message type
 * to external sinks.
 *
 * <p>The cache is intentionally process-wide (not per-runtime / per-bus) because the cached
 * value is purely a function of the {@link Class} — there is nothing per-runtime about it.
 * Sharing avoids paying the cache cost twice for the same class when both the command bus
 * and the event bus need its name.
 *
 * <h2>Memory model</h2>
 *
 * {@link ClassValue} retains a strong reference to its computed value for the lifetime of
 * the {@link Class}; when the class is collected (typically only on classloader shutdown,
 * e.g. hot-redeploy in a container), the cached entry follows. There is no leak risk in
 * the typical long-running JVM, and no explicit eviction is needed for hot-redeploy
 * scenarios (the classloader's GC drops the entire ClassValue entry).
 */
public final class ClassNameCache {

    private ClassNameCache() {
        // utility
    }

    private static final ClassValue<String> CACHE = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return type.getName();
        }
    };

    /**
     * Returns the cached {@code type.getName()} for {@code type}. Lock-free read after the
     * first computation per {@link Class}.
     *
     * @param type the class whose binary name to retrieve; must not be {@code null}
     * @return the result of {@code type.getName()}; never {@code null}
     */
    public static String get(Class<?> type) {
        return CACHE.get(type);
    }
}
