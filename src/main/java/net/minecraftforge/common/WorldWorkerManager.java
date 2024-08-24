/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class WorldWorkerManager {
    private static List<IWorker> workers = new ArrayList<IWorker>();
    private static long startTime = -1;
    private static int index = 0;
    private static final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public static void tick(boolean start) {
        if (start) {
            startTime = System.currentTimeMillis();
            return;
        }

        index = 0;
        IWorker task = getNext();
        if (task == null)
            return;

        long time = 50 - (System.currentTimeMillis() - startTime);
        if (time < 10)
            time = 10; // If ticks are lagging, give us at least 10ms to do something.
        time += System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();

        while (System.currentTimeMillis() < time && task != null) {
            final IWorker currentTask = task; // Capture the current task for the lambda
            futures.add(executor.submit(() -> {
                boolean again = currentTask.doWork();

                if (!currentTask.hasWork()) {
                    remove(currentTask);
                } else if (!again) {
                    synchronized (WorldWorkerManager.class) {
                        index--; // Decrement index to reprocess this task in the next tick
                    }
                }
            }));

            task = getNext();
        }

        for (Future<?> future : futures) {
            try {
                future.get(); // Ensure all tasks complete
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static synchronized void addWorker(IWorker worker) {
        workers.add(worker);
    }

    private static synchronized IWorker getNext() {
        return workers.size() > index ? workers.get(index++) : null;
    }

    private static synchronized void remove(IWorker worker) {
        workers.remove(worker);
        index--;
    }

    // Internal only, used to clear everything when the server shuts down.
    public static synchronized void clear() {
        workers.clear();
    }

    public static interface IWorker {
        boolean hasWork();

        /**
         * Perform a task, returning true from this will have the manager call this function again this tick if there is time left.
         * Returning false will skip calling this worker until next tick.
         */
        boolean doWork();
    }
}
