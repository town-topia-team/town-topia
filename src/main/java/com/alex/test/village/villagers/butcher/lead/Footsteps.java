package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.entity.NpcEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The villager's last few footsteps on its walk over to an animal, kept so
 * a lead put on from a distance ({@link #canLeadFromHere}) has floor to bring
 * the animal up onto ({@link LeadWalk#seedFootsteps}).
 */
public final class Footsteps {

    private static final double SPACING = 0.75;
    private static final int MAX_STEPS = 8;
    /** A lead goes on from this far when the animal cannot be walked up to; under where the trail calls it too far. */
    public static final double FAR_LEAD_RANGE = 7.0;
    /** Footsteps kept for the trail lie this close to the villager, so a bring-up can land on one. */
    private static final double KEEP_NEAR = 4.0;

    private final ArrayDeque<Vec3> steps = new ArrayDeque<>();

    public void clear() {
        steps.clear();
    }

    /** Called every tick of the walk over: a step every 0.75 blocks, on the ground. */
    public void record(NpcEntity npc) {
        if (!npc.onGround() || npc.isInWater()) return;
        Vec3 feet = npc.position();
        Vec3 last = steps.peekLast();
        if (last != null && last.distanceToSqr(feet) < SPACING * SPACING) return;
        if (steps.size() >= MAX_STEPS) steps.pollFirst();
        steps.addLast(feet);
    }

    /**
     * Whether the villager, unable to walk up to the animal, may put the lead
     * on from where it stands: near enough, in sight (never through a wall),
     * and with footsteps here to bring it up onto.
     */
    public boolean canLeadFromHere(NpcEntity npc, Animal animal) {
        if (npc.distanceTo(animal) > FAR_LEAD_RANGE || !npc.hasLineOfSight(animal)) return false;
        // A bring-up lands no nearer the villager than 1.5: one step must lie past that.
        for (Vec3 step : near(npc)) {
            if (step.distanceTo(npc.position()) >= 1.5) return true;
        }
        return false;
    }

    /** The footsteps near the villager, oldest first. */
    public List<Vec3> near(NpcEntity npc) {
        List<Vec3> out = new ArrayList<>();
        for (Vec3 step : steps) {
            if (step.distanceTo(npc.position()) <= KEEP_NEAR) out.add(step);
        }
        return out;
    }
}
