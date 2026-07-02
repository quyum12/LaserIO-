package com.direwolf20.laserio.common.util;

import com.direwolf20.laserio.common.blockentities.LaserNodeBE;
import com.direwolf20.laserio.setup.Config;
import net.minecraft.world.level.Level;

import java.util.*;

public class LaserScheduler {
    private static final Map<Level, SchedulerState> states = new HashMap<>();

    public static class SchedulerState {
        public final ArrayDeque<LaserNodeBE> activeQueue = new ArrayDeque<>();
        public final Set<LaserNodeBE> cooldownList = new LinkedHashSet<>();
        public final Set<LaserNodeBE> wakeQueue = new LinkedHashSet<>();
    }

    public static void tick(Level level) {
        if (level.isClientSide) return;
        SchedulerState state = states.get(level);
        if (state == null) return;

        long start = System.nanoTime();
        long budgetNs = (long) (Config.MAX_TICK_MS.get() * 1_000_000);
        long currentTick = level.getGameTime();

        // 1. Coalesce wake requests from previous tick into active queue
        if (!state.wakeQueue.isEmpty()) {
            for (LaserNodeBE node : state.wakeQueue) {
                if (node.getNodeState() != LaserNodeBE.NodeState.ACTIVE) {
                    node.setNodeState(LaserNodeBE.NodeState.ACTIVE);
                    state.activeQueue.add(node);
                }
            }
            state.wakeQueue.clear();
        }

        // 2. Promote from Cooldown to Active
        Iterator<LaserNodeBE> cooldownIterator = state.cooldownList.iterator();
        while (cooldownIterator.hasNext()) {
            LaserNodeBE node = cooldownIterator.next();
            if (currentTick >= node.getNextAvailableTime()) {
                cooldownIterator.remove();
                if (node.getNodeState() != LaserNodeBE.NodeState.ACTIVE) {
                    node.setNodeState(LaserNodeBE.NodeState.ACTIVE);
                    state.activeQueue.add(node);
                }
                node.setWakeRequestedThisTick(false);
            }
        }

        // 3. Overload Protection
        int cooldownModifier = 0;
        if (state.activeQueue.size() > 1000) { // Threshold for overload
            cooldownModifier = Math.min(20, state.activeQueue.size() / 500);
        }

        // 4. Execute Active Nodes
        int processedThisTick = 0;
        int initialActiveCount = state.activeQueue.size();

        while (!state.activeQueue.isEmpty() && processedThisTick < initialActiveCount) {
            // Strict primary budget check
            if (System.nanoTime() - start >= budgetNs) break;

            LaserNodeBE node = state.activeQueue.poll();
            processedThisTick++;

            if (node == null || node.isRemoved()) continue;

            // Execute node work
            LaserNodeBE.NodeWorkResult result = node.doNodeTick();
            node.setWakeRequestedThisTick(false);

            if (result.didWork()) {
                int cooldown = result.cooldownTicks() + cooldownModifier;
                node.setNextAvailableTime(currentTick + cooldown);
                node.setNodeState(LaserNodeBE.NodeState.COOLDOWN);
                state.cooldownList.add(node);
            } else {
                if (result.cooldownTicks() > 0) {
                    int cooldown = result.cooldownTicks() + cooldownModifier;
                    node.setNextAvailableTime(currentTick + cooldown);
                    node.setNodeState(LaserNodeBE.NodeState.COOLDOWN);
                    state.cooldownList.add(node);
                } else {
                    node.setNodeState(LaserNodeBE.NodeState.IDLE);
                }
            }
        }
    }

    public static void requestWakeUp(LaserNodeBE node) {
        if (node.getLevel() == null || node.getLevel().isClientSide || node.isRemoved()) return;

        // Wake coalescing: if already requested this tick, ignore
        if (node.isWakeRequestedThisTick()) return;

        SchedulerState state = states.computeIfAbsent(node.getLevel(), k -> new SchedulerState());

        // Burst absorption: wake events go into wakeQueue, NOT activeQueue immediately
        state.wakeQueue.add(node);
        node.setWakeRequestedThisTick(true);
        node.setNextAvailableTime(0); // Ready for next tick promotion
    }

    public static void addNode(LaserNodeBE node) {
        if (node.getLevel() == null || node.getLevel().isClientSide) return;
        requestWakeUp(node);
    }

    public static void removeNode(LaserNodeBE node) {
        if (node.getLevel() == null) return;
        SchedulerState state = states.get(node.getLevel());
        if (state != null) {
            state.activeQueue.remove(node);
            state.cooldownList.remove(node);
            state.wakeQueue.remove(node);
        }
        node.setNodeState(LaserNodeBE.NodeState.IDLE);
        node.setWakeRequestedThisTick(false);
    }

    public static void clear(Level level) {
        states.remove(level);
    }
}
