package org.nexusflow.core.cqrs.event;

import org.nexusflow.core.ddd.DomainEvent;

import java.util.List;

record EventTaskGroup<E extends DomainEvent>(int priority, List<EventTask<E>> tasks) implements Comparable<EventTaskGroup<E>> {
    @Override
    public int compareTo(EventTaskGroup<E> o) {
        return Integer.compare(priority, o.priority);
    }
}