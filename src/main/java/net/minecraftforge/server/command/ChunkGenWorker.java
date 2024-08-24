/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */


package net.minecraftforge.server.command;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.WorldWorkerManager.IWorker;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChunkGenWorker implements IWorker {
    private final CommandSourceStack listener;
    protected final BlockPos start;
    protected final int total;
    private final ServerLevel dim;
    private final Queue<BlockPos> queue;
    private final int notificationFrequency;
    private int lastNotification = 0;
    private long lastNotificationTime = 0;
    private int genned = 0;
    private final ExecutorService executor;

    public ChunkGenWorker(CommandSourceStack listener, BlockPos start, int total, ServerLevel dim, int interval, int numThreads) {
        this.listener = listener;
        this.start = start;
        this.total = total;
        this.dim = dim;
        this.queue = new ConcurrentLinkedQueue<>(buildQueue()); // Use a thread-safe queue
        this.notificationFrequency = interval != -1 ? interval : Math.max(total / 20, 100);
        this.lastNotificationTime = System.currentTimeMillis();
        this.executor = Executors.newFixedThreadPool(numThreads); // Create a thread pool
    }

    protected Queue<BlockPos> buildQueue() {
        Queue<BlockPos> ret = new ArrayDeque<>();
        ret.add(start);

        // Spiral outwards starting from the center
        int radius = 1;
        while (ret.size() < total) {
            for (int q = -radius + 1; q <= radius && ret.size() < total; q++)
                ret.add(start.offset(radius, 0, q));

            for (int q = radius - 1; q >= -radius && ret.size() < total; q--)
                ret.add(start.offset(q, 0, radius));

            for (int q = radius - 1; q >= -radius && ret.size() < total; q--)
                ret.add(start.offset(-radius, 0, q));

            for (int q = -radius + 1; q <= radius && ret.size() < total; q++)
                ret.add(start.offset(q, 0, -radius));

            radius++;
        }
        return ret;
    }

    public Component getStartMessage(CommandSourceStack sender) {
        return Component.translatable("commands.forge.gen.start", total, start.getX(), start.getZ(), dim);
    }

    @Override
    public boolean hasWork() {
        return !queue.isEmpty();
    }

    @Override
    public boolean doWork() {
        while (hasWork()) {
            executor.submit(this::generateChunk);
        }

        executor.shutdown();
        return true;
    }

    private void generateChunk() {
        BlockPos next = queue.poll();
        if (next != null) {
            int x = next.getX();
            int z = next.getZ();

            ChunkAccess chunk = dim.getChunkSource().getChunk(x, z, false); // Modify as necessary

            if (chunk != null) {
                genned++;
            }

            synchronized (this) {
                if (++lastNotification >= notificationFrequency || lastNotificationTime < System.currentTimeMillis() - 60 * 1000) {
                    listener.sendSuccess(Component.translatable("commands.forge.gen.progress", total - queue.size(), total), true);
                    lastNotification = 0;
                    lastNotificationTime = System.currentTimeMillis();
                }
            }
        }

        if (queue.isEmpty()) {
            listener.sendSuccess(Component.translatable("commands.forge.gen.complete", genned, total, dim.dimension().location()), true);
        }
    }
}
