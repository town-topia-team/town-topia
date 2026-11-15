package com.alex.test.village.brain;

import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.registry.VillagerRegistry;

/**
 * Abstract base for all profession work activities.
 *
 * <p>Concrete subclasses implement {@link #doTick} and optionally
 * {@link #start} and {@link #doStop}. Motivation drain is handled centrally
 * by the stat system.
 */
public abstract class WorkActivity extends Activity {

    /** Return the work activity for a profession, or {@code null} when absent. */
    public static WorkActivity create(Profession profession) {
        return VillagerRegistry.createWorkActivity(profession);
    }

    /**
     * Routine work for this villager right now, which is a deposit trip when
     * a tool it cannot work without is gone and cannot be replaced.
     */
    public static WorkActivity create(Profession profession, Context ctx) {
        return VillagerRegistry.createWorkActivity(profession, ctx);
    }

    @Override
    protected final Status tick(Context ctx) {
        return doTick(ctx);
    }

    @Override
    public final void stop(Context ctx) {
        doStop(ctx);
    }

    @Override
    public String name() {
        return "work";
    }

    protected abstract Status doTick(Context ctx);

    /**
     * True only while walking to a work site, allowing incidental conversation.
     */
    public boolean isMovingPhase() {
        return false;
    }

    /**
     * Whether a villager walking in this work may be stopped for a chat. Most
     * walks can wait; a cleric running to someone hurt cannot.
     */
    public boolean allowsChatWhileWalking() {
        return true;
    }

    /**
     * Whether routine brain overrides may interrupt this work right now.
     * Emergency fleeing is handled separately by {@link VillagerBrain}.
     */
    public boolean allowsRoutineInterruption() {
        return true;
    }

    /**
     * Whether a villager doing this work right now stays where it is when the
     * alarm bell sounds, because it is already indoors and should be: a class
     * in session. Everyone else heads for shelter.
     */
    public boolean sheltersInPlace() {
        return false;
    }

    /** Whether a settings change leaves this work running: it has something in hand to finish first. */
    public boolean holdsThroughSettingsChange() {
        return false;
    }

    /** Release work state when this activity is interrupted or completed. */
    protected void doStop(Context ctx) {}
}
