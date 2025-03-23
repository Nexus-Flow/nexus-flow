package net.nexus_flow.core.cqrs.command;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class ThreadContext {
    private Long threadId;
    private ThreadContext parent;
    private final List<ThreadContext> childContexts = new CopyOnWriteArrayList<>();
    private TaskType taskType;
    private boolean errorFlag;

    public Long getThreadId() {
        return threadId;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

    public ThreadContext getParent() {
        return parent;
    }

    public void setParent(ThreadContext parent) {
        this.parent = parent;
    }

    public List<ThreadContext> getChildContexts() {
        return childContexts;
    }

    public void addChild(ThreadContext childContext) {
        childContexts.add(childContext);
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public boolean isErrorFlag() {
        return errorFlag;
    }

    public void setErrorFlag(boolean errorFlag) {
        this.errorFlag = errorFlag;
    }

    public List<Long> getChildIds() {
        return this.childContexts.stream()
                .map(ThreadContext::getThreadId)
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "ThreadContext{" +
                "threadId=" + threadId +
                ", parent=" + parent +
                ", taskType=" + taskType +
                ", errorFlag=" + errorFlag +
                '}';
    }

    public void notifyUncaughtException(RuntimeException ex) {
        // Marca el contexto como cancelado
        this.errorFlag = true;

        // Propaga la excepción a la tarea padre (si existe)
        if (parent != null) {
            System.out.println("Propagating exception to parent: " + parent.getThreadId());
            parent.notifyUncaughtException(ex);
        } else {
            // Solo lanza la excepción para la tarea raíz (que no tiene padre)
            Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), ex);
            throw ex;
        }
    }

//    public void notifyUncaughtException(RuntimeException ex) {
//        // If the error flag is already set, don't propagate further
//        if (this.errorFlag) {
//            return;
//        }
//
//        // Mark the context as cancelled
//        this.errorFlag = true;
//
//        // Propagate the exception to the parent task (if it exists)
//        if (parent != null) {
//            System.out.println("Propagating exception to parent: " + parent.getThreadId());
//            parent.notifyUncaughtException(ex);
//        } else {
//            // Only throw the exception for the root task (which has no parent)
//            Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), ex);
//            throw ex;
//        }
//    }

}