package net.nexus_flow.core.cqrs.query;

import java.util.logging.Level;
import java.util.logging.Logger;

sealed interface QueryHandler<T extends Record, R> permits AbstractQueryHandler {

    R handle(T query);


    /**
     * @return the error handler for this query handler.
     */
    default QueryErrorHandler getErrorHandler() {
        return e -> {
            // Log the error at least, so it's not swallowed silently
            Logger logger = Logger.getLogger(this.getClass().getName());
            logger.log(Level.SEVERE, "Unhandled error: ", e);
        };
    }

}
