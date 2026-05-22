package net.nexus_flow.core.runtime.reflect;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Pins the four-way semantic table of {@link ReflectiveInvocationPropagator#propagate}:
 *
 * <ol>
 * <li>cause = RuntimeException — returned verbatim, identity preserved.
 * <li>cause = Error — propagated by THROW (not return) to honour JVM error semantics.
 * <li>cause = checked Exception — wrapped in IllegalStateException with role label + method
 * signature in the message; cause chain preserved.
 * <li>cause = null — wrapped in IllegalStateException; the original {@link
 * InvocationTargetException} becomes the cause so the stack trace is preserved.
 * </ol>
 *
 * <p>Centralising this in one helper eliminated the three identical {@code propagate(...)} methods
 * that used to live in {@code CommandHandlerMethodAdapter}, {@code
 * DomainEventListenerMethodAdapter}, and {@code QueryHandlerMethodAdapter} — each differing only in
 * the role label.
 */
class ReflectiveInvocationPropagatorTest {

    static final class Sample {
        public void method() {
            /* sample */
        }
    }

    private static Method sampleMethod() throws NoSuchMethodException {
        return Sample.class.getMethod("method");
    }

    @Test
    void runtimeExceptionCause_returnedVerbatim_identityPreserved() throws Exception {
        Method                    m        = sampleMethod();
        IllegalArgumentException  original = new IllegalArgumentException("nope");
        InvocationTargetException ite      = new InvocationTargetException(original);
        RuntimeException          result   = ReflectiveInvocationPropagator.propagate("Handler method", m, ite);
        assertSame(
                   original,
                   result,
                   "RuntimeException causes must be returned by-identity so the original stack trace and"
                           + " any subclass information survive — no wrapping");
    }

    @Test
    void errorCause_isThrown_notReturned() throws Exception {
        Method                    m        = sampleMethod();
        OutOfMemoryError          original = new OutOfMemoryError("test");
        InvocationTargetException ite      = new InvocationTargetException(original);
        Error                     thrown   =
                assertThrows(
                             OutOfMemoryError.class,
                             () -> ReflectiveInvocationPropagator.propagate("Handler method", m, ite));
        assertSame(
                   original,
                   thrown,
                   "Errors must propagate by-identity — never wrapped, never converted to RuntimeException");
    }

    @Test
    void checkedExceptionCause_wrappedInIllegalStateException_withRoleLabelAndMethodSignature() throws Exception {
        Method                    m        = sampleMethod();
        IOException               original = new IOException("io boom");
        InvocationTargetException ite      = new InvocationTargetException(original);
        RuntimeException          result   = ReflectiveInvocationPropagator.propagate("Listener method", m, ite);
        assertTrue(result instanceof IllegalStateException, "wrap class must be IllegalStateException");
        assertSame(original, result.getCause(), "wrapped cause must be the original checked exception");
        assertNotNull(result.getMessage());
        assertTrue(
                   result.getMessage().contains("Listener method"),
                   "message must include the role label so operators can tell which adapter rethrew it; got: "
                           + result.getMessage());
        assertTrue(
                   result.getMessage().contains("method"),
                   "message must include the method name for triage; got: " + result.getMessage());
    }

    @Test
    void nullCause_wrappedInIllegalStateException_withIteAsCause_preservingStack() throws Exception {
        Method m = sampleMethod();
        // InvocationTargetException with a null cause is rare but legal (some classloading paths
        // surface it). The wrap must use the ITE itself as the cause so the stack trace survives.
        InvocationTargetException ite    = new InvocationTargetException(null);
        RuntimeException          result =
                ReflectiveInvocationPropagator.propagate("Query handler method", m, ite);
        assertTrue(result instanceof IllegalStateException);
        assertSame(
                   ite,
                   result.getCause(),
                   "when the original cause is null, the ITE itself must become the wrap's cause so the"
                           + " reflective-invocation frame stays attached to the stack trace");
        assertTrue(
                   result.getMessage().contains("Query handler method"),
                   "message must include the role label even when the underlying cause is null");
    }

    @Test
    void roleLabel_isInterpolatedVerbatim_intoMessage() throws Exception {
        Method                    m      = sampleMethod();
        InvocationTargetException ite    = new InvocationTargetException(new IOException());
        String                    label  = "ProjectionStep#42[]";
        RuntimeException          result = ReflectiveInvocationPropagator.propagate(label, m, ite);
        assertTrue(
                   result.getMessage().startsWith(label),
                   "role label is interpolated verbatim at the head of the message; got: "
                           + result.getMessage());
    }

    @Test
    void utilityClass_isNotInstantiable() throws Exception {
        var ctor = ReflectiveInvocationPropagator.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();
        assertEquals(
                     ReflectiveInvocationPropagator.class,
                     instance.getClass(),
                     "constructor is private — accessible only via setAccessible; this asserts the class"
                             + " has no public construction surface");
    }
}
