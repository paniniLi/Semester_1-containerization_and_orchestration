package org.panini.batch;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BatchLoadService {

    private static final Logger log = LoggerFactory.getLogger(BatchLoadService.class);
    private static final int ALLOCATION_BLOCK_BYTES = 1024 * 1024;
    private static final int MEMORY_PAGE_BYTES = 4096;

    private final int cpuThreads;
    private final int memoryMegabytes;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<byte[]> retainedMemory = new ArrayList<>();
    private final ExecutorService executor;

    private volatile long cpuResult;

    public BatchLoadService(
            @Value("${batch.cpu-threads:1}") int cpuThreads,
            @Value("${batch.memory-megabytes:128}") int memoryMegabytes
    ) {
        if (cpuThreads < 1) {
            throw new IllegalArgumentException("batch.cpu-threads must be at least 1");
        }
        if (memoryMegabytes < 1) {
            throw new IllegalArgumentException("batch.memory-megabytes must be at least 1");
        }

        this.cpuThreads = cpuThreads;
        this.memoryMegabytes = memoryMegabytes;
        this.executor = Executors.newFixedThreadPool(cpuThreads, new BatchThreadFactory());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        allocateAndTouchMemory();
        for (int thread = 0; thread < cpuThreads; thread++) {
            executor.submit(this::consumeCpu);
        }

        log.info("Batch load started with {} CPU thread(s) and {} MiB of retained memory",
                cpuThreads, memoryMegabytes);
    }

    private void allocateAndTouchMemory() {
        long remainingBytes = Math.multiplyExact((long) memoryMegabytes, 1024L * 1024L);

        while (remainingBytes > 0) {
            int blockSize = (int) Math.min(remainingBytes, ALLOCATION_BLOCK_BYTES);
            byte[] block = new byte[blockSize];

            // Touch every page so the operating system commits physical memory.
            for (int offset = 0; offset < block.length; offset += MEMORY_PAGE_BYTES) {
                block[offset] = 1;
            }
            block[block.length - 1] = 1;

            retainedMemory.add(block);
            remainingBytes -= blockSize;
        }
    }

    private void consumeCpu() {
        long value = Thread.currentThread().threadId() | 1L;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            for (int iteration = 0; iteration < 1_000_000; iteration++) {
                value = Long.rotateLeft(value * 0x9E3779B97F4A7C15L + iteration, 13);
            }

            // Publishing the result prevents the JIT compiler from removing the loop.
            cpuResult = value;
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
        retainedMemory.clear();
    }

    private static final class BatchThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "batch-cpu-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }
}
