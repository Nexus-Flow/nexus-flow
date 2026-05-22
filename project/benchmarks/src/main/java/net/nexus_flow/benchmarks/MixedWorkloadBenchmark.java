package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Mixed-workload benchmark approximating a realistic microservice traffic mix:
 *
 * <ul>
 * <li>70 % command dispatch (write-side, hot path).
 * <li>20 % query dispatch (read-side, also hot but cheaper).
 * <li>10 % event dispatch (saga/audit fan-out, less frequent but more listeners).
 * </ul>
 *
 * <p>Each benchmark iteration picks one of the three paths uniformly using
 * {@link ThreadLocalRandom} so the JIT cannot pre-compile a deterministic call pattern. The
 * mix forces the runtime's executor pool, registry, and interceptor chain to handle the same
 * mix of dispatches a production deployment sees.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class MixedWorkloadBenchmark {

    public record Cmd(int seq) {
    }

    public record Qry(int seq) {
    }

    public static final class Evt extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public Evt(String aggId) {
            super(aggId);
        }
    }

    public static final class AuditListener extends AbstractDomainEventListener<Evt> {
        public int count;

        @Override
        public void handle(Evt event) {
            count++;
        }
    }

    private FlowRuntime      runtime;
    private Command<Cmd>     cmd;
    private Query<Qry>       qry;
    private Evt              evt;
    private ExecutionContext ctx;

    @Setup
    public void setup() {
        runtime = FlowRuntime.builder().build();
        runtime.commands().register(new AbstractReturnCommandHandler<Cmd, Integer>() {
            @Override
            protected Integer handle(Cmd cmd) {
                return cmd.seq();
            }
        });
        runtime.queries().register(new AbstractQueryHandler<Qry, String>() {
            @Override
            public String handle(Qry body) {
                return "q-" + body.seq();
            }
        });
        runtime.events().register(new AuditListener());
        cmd = Command.<Cmd>builder().body(new Cmd(1)).build();
        qry = Query.<Qry>builder().body(new Qry(1)).build();
        evt = new Evt("mixed");
        ctx = ExecutionContext.root();
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void mixedDispatch(Blackhole bh) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 70) {
            bh.consume(runtime.commands().dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast()));
        } else if (roll < 90) {
            bh.consume(runtime.queries().ask(qry));
        } else {
            bh.consume(runtime.events().dispatchResult(evt, ctx, ErrorPolicy.failFast()));
        }
    }
}
