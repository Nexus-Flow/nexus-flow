package net.nexus_flow.core.ddd.exceptions;

import java.io.Serial;
import java.util.Objects;

/**
 * Abstract base class for domain errors in the DDD model.
 *
 * <p>Domain errors represent exceptional conditions that violate domain invariants or business
 * rules. They wrap both an error code (for programmatic handling and routing) and a descriptive
 * message (for logging and user-facing display).
 *
 * <p>Subclasses must override this class to define specific domain errors relevant to their
 * business domain. Each error type should use a stable error code to enable downstream systems
 * (error handlers, UI, monitoring) to route and respond appropriately.
 *
 * <p>Example:
 *
 * <p>
 *
 * {@snippet :
 * // Custom domain error for insufficient funds
 * public final class InsufficientFundsError extends DomainError {
 *     public InsufficientFundsError(BigDecimal required, BigDecimal available) {
 *         super("ERR_INSUFFICIENT_FUNDS",
 *               "Required: " + required + ", available: " + available);
 *     }
 * }
 * }
 *
 * @see RuntimeException for the exception hierarchy
 */
public abstract class DomainError extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final String errorMessage;

    /**
     * Constructs a domain error with the given error code and message.
     *
     * <p>The error message is used as the exception message. No underlying cause is recorded.
     *
     * @param errorCode    a stable, programmatic error identifier (e.g. "ERR_INVALID_ORDER_STATE");
     *                     never {@code null}
     * @param errorMessage a human-readable error description; never {@code null}
     * @throws NullPointerException if {@code errorCode} or {@code errorMessage} is {@code null}
     */
    protected DomainError(String errorCode, String errorMessage) {
        super(Objects.requireNonNull(errorMessage, "errorMessage"));

        this.errorCode    = Objects.requireNonNull(errorCode, "errorCode");
        this.errorMessage = errorMessage;
    }

    /**
     * Constructs a domain error with the given error code, message, and underlying cause.
     *
     * <p>This constructor preserves the underlying cause so it is visible via {@link
     * Throwable#getCause()} and in stack traces, enabling better diagnostics.
     *
     * @param errorCode    a stable, programmatic error identifier (e.g. "ERR_DATABASE_FAULT"); never
     *                     {@code null}
     * @param errorMessage a human-readable error description; never {@code null}
     * @param cause        the underlying exception that triggered this error; may be {@code null}
     * @throws NullPointerException if {@code errorCode} or {@code errorMessage} is {@code null}
     */
    protected DomainError(String errorCode, String errorMessage, Throwable cause) {
        super(Objects.requireNonNull(errorMessage, "errorMessage"), cause);

        this.errorCode    = Objects.requireNonNull(errorCode, "errorCode");
        this.errorMessage = errorMessage;
    }

    /**
     * Returns the stable, programmatic error code.
     *
     * <p>This code can be used for routing, monitoring, and error recovery logic.
     *
     * @return the error code; never {@code null}
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the descriptive error message.
     *
     * @return the error message; never {@code null}
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
