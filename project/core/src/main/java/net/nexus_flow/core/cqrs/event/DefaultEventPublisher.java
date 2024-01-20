package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

class DefaultEventPublisher<E extends DomainEvent> implements EventPublisher<E> {
    private static final Logger logger = Logger.getLogger(DefaultEventPublisher.class.getName());
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, PriorityQueue<EventTaskGroup<E>>> eventTasks;
    private final List<DomainEventListener<E>> listeners = new ArrayList<>();
    private volatile boolean running;

    public DefaultEventPublisher() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        eventTasks = new ConcurrentHashMap<>();
        running = true;
    }

    @Override
    public void addListener(DomainEventListener<E> listener) {
        listeners.add(listener);
    }

    @Override
    public boolean isListenerListEmpty() {
        return listeners.isEmpty();
    }

    @Override
    public void removeListener(DomainEventListener<E> listener) {
        listeners.remove(listener);
    }

    @Override
    public void publish(E event, boolean isSaga) {
        Map<Integer, List<EventTask<E>>> tasksByPriorityMap = new HashMap<>();
        for (DomainEventListener<E> listener : listeners) {
            EventTask<E> eventTask = new EventTask<>(event, listener, isSaga);
            tasksByPriorityMap.computeIfAbsent(listener.order(), _ -> new ArrayList<>()).add(eventTask);
        }

        if (isSaga) {
            executeTasksImmediately(tasksByPriorityMap);
        } else {
            enqueueTasks(tasksByPriorityMap, event.getAggregateId());
            processQueue(event.getAggregateId());
        }
    }

    private void executeTasksImmediately(Map<Integer, List<EventTask<E>>> tasksByPriorityMap) {
        // Sort the entries by the key (which is the priority)
        List<Map.Entry<Integer, List<EventTask<E>>>> sortedEntries = tasksByPriorityMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        // Execute tasks for each entry
        for (Map.Entry<Integer, List<EventTask<E>>> entry : sortedEntries) {
            List<EventTask<E>> tasks = entry.getValue();

            // Convert tasks into CompletableFuture array
            CompletableFuture<?>[] futures = tasks.stream()
                    .map(task -> CompletableFuture.runAsync(task, executor))
                    .toArray(CompletableFuture[]::new);

            // Wait for all tasks to complete
            CompletableFuture.allOf(futures).join();
        }
    }

    private void enqueueTasks(Map<Integer, List<EventTask<E>>> tasksByPriorityMap, String aggregateId) {
        PriorityQueue<EventTaskGroup<E>> queue = eventTasks.computeIfAbsent(aggregateId, _ -> new PriorityQueue<>());

        // Sort the entries by the key (which is the priority)
        tasksByPriorityMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> queue.offer(new EventTaskGroup<>(entry.getKey(), entry.getValue())));
    }

//    private void processQueue(String aggregateId) {
//        PriorityQueue<EventTaskGroup<E>> queue = eventTasks.get(aggregateId);
//        if (queue == null) {
//            return;
//        }
//
//        CompletableFuture.runAsync(() -> {               // ** Wrap the tasks execution inside a CompletableFuture.runAsync
//            while (!queue.isEmpty() && running) {         // ** Check the running status
//                EventTaskGroup<E> taskGroup = queue.poll();
//                List<EventTask<E>> tasks = taskGroup.tasks();
//
//                // Convert tasks into CompletableFuture array
//                CompletableFuture<?>[] futures = tasks.stream()
//                        .map(task -> CompletableFuture.runAsync(task, executor))
//                        .toArray(CompletableFuture[]::new);
//
//                // Wait for all tasks to complete
//                CompletableFuture.allOf(futures).join();
//            }
//        }, executor);
//    }

    private void processQueue(String aggregateId) {
        PriorityQueue<EventTaskGroup<E>> queue = eventTasks.get(aggregateId);
        if (queue == null) {
            return;
        }

        // See 'scheduleNext' method after this snippet
        scheduleNext(queue);
    }

    private void scheduleNext(PriorityQueue<EventTaskGroup<E>> queue) {
        EventTaskGroup<E> taskGroup = queue.poll();
        if (taskGroup != null) {
            List<EventTask<E>> tasks = taskGroup.tasks();
            CompletableFuture<?>[] futures = tasks.stream()
                    .map(task -> CompletableFuture.runAsync(task, executor))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).whenComplete((_, _) -> scheduleNext(queue));
        }
    }

    @Override
    public void close() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}