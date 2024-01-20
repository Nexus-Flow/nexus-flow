//import org.junit.jupiter.api.Assertions;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.TestInstance;
//import net.nexus_flow.core.cqrs.command.*;
//
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.AtomicReference;
//import java.util.logging.Logger;
//
//@TestInstance(TestInstance.Lifecycle.PER_METHOD)
//public class NoReturnCommandHandlerTest {
//
//    private static final Logger logger = Logger.getLogger(NoReturnCommandHandlerTest.class.getName());
//
//    @Test
//    public void testPriorityExecutionWithDelay() throws InterruptedException {
//        int concurrencyLevel = 1;
//        CountDownLatch latch = new CountDownLatch(3);
//        List<RecordTest> results = Collections.synchronizedList(new ArrayList<>());
//
//        NoReturnCommandHandler<RecordTest> handler = new AbstractNoReturnCommandHandler<>() {
//            @Override
//            public int getConcurrencyLevel() {
//                return concurrencyLevel;
//            }
//
//            @Override
//            public void handle(RecordTest command) {
//                logger.info("Starting to handle command: " + command);
//                try {
//                    TimeUnit.SECONDS.sleep(2); // Each command handling takes 2 seconds instead of 60
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//                logger.info("Finished handling command: " + command);
//                results.add(command);
//                latch.countDown();
//            }
//        };
//
//        NoReturnHandlerExecutor<RecordTest> executor = new NoReturnHandlerExecutor<>(handler);
//
//        Command<RecordTest> lowPriority = Command.<RecordTest>builder().body(new RecordTest("Low Priority")).priority(1).build();
//        Command<RecordTest> mediumPriority = Command.<RecordTest>builder().body(new RecordTest("Medium Priority")).priority(2).build();
//        Command<RecordTest> highPriority = Command.<RecordTest>builder().body(new RecordTest("High Priority")).priority(3).build();
//
//        executor.execute(lowPriority);
//        TimeUnit.MILLISECONDS.sleep(200);
//        executor.execute(mediumPriority);
//        TimeUnit.MILLISECONDS.sleep(200);
//        executor.execute(highPriority);
//
//        boolean completedInTime = latch.await((3 + 1) * 2, TimeUnit.SECONDS);
//        Assertions.assertTrue(completedInTime, "Not all tasks completed in time");
//        logger.info(STR."Processed Results: \{results}");
//
//        executor.close();
//
//        Assertions.assertEquals(3, results.size());
//        Assertions.assertEquals("Low Priority", results.get(0).id());
//        Assertions.assertEquals("High Priority", results.get(1).id());
//        Assertions.assertEquals("Medium Priority", results.get(2).id());
//    }
//
//    @Test
//    public void testConcurrentPriorityExecution() throws InterruptedException {
//        int concurrencyLevel = 2;
//        CountDownLatch latch = new CountDownLatch(5);
//        List<RecordTest> results = Collections.synchronizedList(new ArrayList<>());
//
//        NoReturnCommandHandler<RecordTest> handler = new AbstractNoReturnCommandHandler<>() {
//            @Override
//            public int getConcurrencyLevel() {
//                return concurrencyLevel;
//            }
//
//            @Override
//            public void handle(RecordTest command) {
//                logger.info("Starting to handle command: " + command);
//                try {
//                    TimeUnit.SECONDS.sleep(2);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//                logger.info("Finished handling command: " + command);
//                results.add(command);
//                latch.countDown();
//            }
//        };
//
//        NoReturnHandlerExecutor<RecordTest> executor = new NoReturnHandlerExecutor<>(handler);
//
//        Command<RecordTest> command1 = Command.<RecordTest>builder().body(new RecordTest("Command1")).priority(5).build();
//        Command<RecordTest> command2 = Command.<RecordTest>builder().body(new RecordTest("Command2")).priority(4).build();
//        Command<RecordTest> command3 = Command.<RecordTest>builder().body(new RecordTest("Command3")).priority(3).build();
//        Command<RecordTest> command4 = Command.<RecordTest>builder().body(new RecordTest("Command4")).priority(2).build();
//        Command<RecordTest> command5 = Command.<RecordTest>builder().body(new RecordTest("Command5")).priority(1).build();
//
//        executor.execute(command1);
//        executor.execute(command2);
//        executor.execute(command3);
//        executor.execute(command4);
//        executor.execute(command5);
//
//        boolean completedInTime = latch.await((command1.getPriority() + 1) * 2L, TimeUnit.SECONDS);
//        Assertions.assertTrue(completedInTime, "Not all tasks completed in time");
//        logger.info("Processed Results: " + results);
//
//        executor.close();
//
//        Assertions.assertEquals(5, results.size());
//
//        // Verificar que Command1 y Command2 estén en la posición 0 y 1, sin importar su orden
//        if (results.get(0).id().equals("Command1")) {
//            Assertions.assertEquals("Command2", results.get(1).id());
//        } else if (results.get(0).id().equals("Command2")) {
//            Assertions.assertEquals("Command1", results.get(1).id());
//        } else {
//            Assertions.fail("Expected either Command1 or Command2 in position 0 and 1");
//        }
//
//        // Verificar que Command3 y Command4 estén en la posición 2 y 3, sin importar su orden
//        if (results.get(2).id().equals("Command3")) {
//            Assertions.assertEquals("Command4", results.get(3).id());
//        } else if (results.get(2).id().equals("Command4")) {
//            Assertions.assertEquals("Command3", results.get(3).id());
//        } else {
//            Assertions.fail("Expected either Command3 or Command4 in position 2 and 3");
//        }
//
//        Assertions.assertEquals("Command5", results.get(4).id());
//    }
//
//    @Test
//    public void testConcurrentPriorityExecutionWithExpectedParallelism() throws InterruptedException {
//        int concurrencyLevel = 2;
//        CountDownLatch latch = new CountDownLatch(5);
//        AtomicInteger concurrentTaskCount = new AtomicInteger(0);
//        List<RecordTest> results = Collections.synchronizedList(new ArrayList<>());
//        AtomicReference<AssertionError> failure = new AtomicReference<>();
//
//        NoReturnCommandHandler<RecordTest> handler = new AbstractNoReturnCommandHandler<>() {
//            @Override
//            public int getConcurrencyLevel() {
//                return concurrencyLevel;
//            }
//
//            @Override
//            public void handle(RecordTest command) {
//                logger.info("Starting to handle command: " + command);
//                concurrentTaskCount.incrementAndGet();
//                try {
//                    TimeUnit.SECONDS.sleep(2);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//                if (concurrentTaskCount.get() > concurrencyLevel) {
//                    failure.set(new AssertionError("Concurrency level exceeded"));
//                }
//                concurrentTaskCount.decrementAndGet();
//                logger.info("Finished handling command: " + command);
//                results.add(command);
//                latch.countDown();
//            }
//        };
//
//        NoReturnHandlerExecutor<RecordTest> executor = new NoReturnHandlerExecutor<>(handler);
//
//        Command<RecordTest> command1 = Command.<RecordTest>builder().body(new RecordTest("Command1")).priority(5).build();
//        Command<RecordTest> command2 = Command.<RecordTest>builder().body(new RecordTest("Command2")).priority(4).build();
//        Command<RecordTest> command3 = Command.<RecordTest>builder().body(new RecordTest("Command3")).priority(3).build();
//        Command<RecordTest> command4 = Command.<RecordTest>builder().body(new RecordTest("Command4")).priority(2).build();
//        Command<RecordTest> command5 = Command.<RecordTest>builder().body(new RecordTest("Command5")).priority(1).build();
//
//        executor.execute(command1);
//        executor.execute(command2);
//        executor.execute(command3);
//        executor.execute(command4);
//        executor.execute(command5);
//
//        boolean completedInTime = latch.await((command1.getPriority() + 1) * 2L, TimeUnit.SECONDS);
//        Assertions.assertTrue(completedInTime, "Not all tasks completed in time");
//        logger.info("Processed Results: " + results);
//
//        executor.close();
//
//        Assertions.assertEquals(5, results.size());
//
//        // Verificar que Command1 y Command2 estén en la posición 0 y 1, sin importar su orden
//        if (results.get(0).id().equals("Command1")) {
//            Assertions.assertEquals("Command2", results.get(1).id());
//        } else if (results.get(0).id().equals("Command2")) {
//            Assertions.assertEquals("Command1", results.get(1).id());
//        } else {
//            Assertions.fail("Expected either Command1 or Command2 in position 0 and 1");
//        }
//
//        // Verificar que Command3 y Command4 estén en la posición 2 y 3, sin importar su orden
//        if (results.get(2).id().equals("Command3")) {
//            Assertions.assertEquals("Command4", results.get(3).id());
//        } else if (results.get(2).id().equals("Command4")) {
//            Assertions.assertEquals("Command3", results.get(3).id());
//        } else {
//            Assertions.fail("Expected either Command3 or Command4 in position 2 and 3");
//        }
//
//        Assertions.assertEquals("Command5", results.get(4).id());
//
//        if (failure.get() != null) {
//            throw new RuntimeException(failure.get());
//        }
//    }
//
//    @Test
//    public void testMinimumConcurrencyLevel() throws InterruptedException {
//        int concurrencyLevel = 3; // Nivel de concurrencia
//        CountDownLatch latch = new CountDownLatch(6); // 6 tareas para garantizar la cola llena
//        AtomicInteger concurrentTaskCount = new AtomicInteger(0);
//        AtomicReference<AssertionError> failure = new AtomicReference<>();
//
//        NoReturnCommandHandler<RecordTest> handler = new AbstractNoReturnCommandHandler<>() {
//            @Override
//            public int getConcurrencyLevel() {
//                return concurrencyLevel;
//            }
//
//            @Override
//            public void handle(RecordTest command) {
//                int currentConcurrentTasks = concurrentTaskCount.incrementAndGet();
//
//                if (currentConcurrentTasks < concurrencyLevel && latch.getCount() < concurrencyLevel) {
//                    failure.set(new AssertionError("Concurrent task count dropped below concurrency level"));
//                }
//
//                try {
//                    TimeUnit.MILLISECONDS.sleep(500); // Tiempo más corto para evitar esperas largas
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//
//                concurrentTaskCount.decrementAndGet();
//                latch.countDown();
//            }
//        };
//
//        NoReturnHandlerExecutor<RecordTest> executor = new NoReturnHandlerExecutor<>(handler);
//
//        // Ejecutar múltiples comandos para llenar la cola
//        for (int i = 0; i < 6; i++) {
//            Command<RecordTest> command = Command.<RecordTest>builder().body(new RecordTest("Command" + i)).priority(i).build();
//            executor.execute(command);
//        }
//
//        boolean completedInTime = latch.await(4, TimeUnit.SECONDS);
//        Assertions.assertTrue(completedInTime, "Not all tasks completed in time");
//        executor.close();
//
//        if (failure.get() != null) {
//            throw failure.get();
//        }
//    }
//
//    @Test
//    public void testSelectiveThreadWakeup() throws InterruptedException {
//        int concurrencyLevel = 2; // Nivel de concurrencia
//        CountDownLatch latch = new CountDownLatch(4); // 4 tareas para probar el despertar selectivo
//        AtomicInteger activeThreadCount = new AtomicInteger(0);
//        AtomicReference<AssertionError> failure = new AtomicReference<>();
//
//        NoReturnCommandHandler<RecordTest> handler = new AbstractNoReturnCommandHandler<>() {
//            @Override
//            public int getConcurrencyLevel() {
//                return concurrencyLevel;
//            }
//
//            @Override
//            public void handle(RecordTest command) {
//                if (activeThreadCount.incrementAndGet() > concurrencyLevel) {
//                    failure.set(new AssertionError("More threads woken up than concurrency level"));
//                }
//                try {
//                    TimeUnit.MILLISECONDS.sleep(500); // Tiempo más corto para evitar esperas largas
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    activeThreadCount.decrementAndGet();
//                    latch.countDown();
//                }
//            }
//        };
//
//        NoReturnHandlerExecutor<RecordTest> executor = new NoReturnHandlerExecutor<>(handler);
//
//        // Ejecutar comandos con un pequeño retardo para probar el despertar selectivo
//        for (int i = 0; i < 4; i++) {
//            TimeUnit.MILLISECONDS.sleep(100); // Retardo para permitir el inicio y la suspensión de tareas
//            Command<RecordTest> command = Command.<RecordTest>builder().body(new RecordTest("Command" + i)).priority(i).build();
//            executor.execute(command);
//        }
//
//        boolean completedInTime = latch.await(3, TimeUnit.SECONDS);
//        Assertions.assertTrue(completedInTime, "Not all tasks completed in time");
//        executor.close();
//
//        if (failure.get() != null) {
//            throw failure.get();
//        }
//    }
//
//    @Test
//    public void testBenchmarkCapacity() throws InterruptedException {
//        int numThreads = 10000; // Número de hilos a ejecutar
//        int concurrencyLevel = 10; // Nivel de concurrencia
//        CountDownLatch latch = new CountDownLatch(numThreads);
//
//        NoReturnCommandHandler<RecordTest> handler = new AbstractNoReturnCommandHandler<>() {
//            @Override
//            public int getConcurrencyLevel() {
//                return concurrencyLevel;
//            }
//
//            @Override
//            public void handle(RecordTest command) {
//                try {
//                    Thread.sleep(10);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//                latch.countDown();
//            }
//
//            @Override
//            public InitializationType getInitializationType() {
//                return InitializationType.EAGER;
//            }
//        };
//
//        NoReturnHandlerExecutor<RecordTest> executor = new NoReturnHandlerExecutor<>(handler);
//
//        long startTime = System.currentTimeMillis();
//
//        List<Thread> threads = new ArrayList<>(numThreads);
//
//        for (int i = 0; i < numThreads; i++) {
//            final int threadNumber = i; // Variable final o efectivamente final
//            Thread thread = new Thread(() -> {
//                for (int j = 0; j < concurrencyLevel; j++) {
//                    Command<RecordTest> command = Command.<RecordTest>builder().body(new RecordTest("Command" + threadNumber + "-" + j)).priority(threadNumber).build();
//                    executor.execute(command);
//                }
//            });
//            threads.add(thread);
//            thread.start();
//        }
//
//        for (Thread thread : threads) {
//            thread.join(); // Espera a que todos los hilos finalicen antes de continuar
//        }
//
//        boolean completedInTime = latch.await(1, TimeUnit.MINUTES);
//        Assertions.assertTrue(completedInTime, "Not all tasks completed in time");
//
//        executor.close();
//
//        long endTime = System.currentTimeMillis();
//        long elapsedTime = endTime - startTime;
//
//        System.out.println("Total elapsed time for " + numThreads + " threads with concurrency " + concurrencyLevel + ": " + elapsedTime + " milliseconds");
//        Assertions.assertTrue(elapsedTime < 60000, "Benchmark failed: El tiempo total excede los 60000 ms");
//    }
//
//    record RecordTest(String id) {
//    }
//
//}
