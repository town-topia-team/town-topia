package com.alex.test.village.follow;

/**
 * A judged step.
 *
 * @param kind    what the step asks of the body
 * @param rise    end floor minus start floor for WALK and DROP; for JUMP the biggest single rise
 * @param riserAt distance from the start tile's middle, along the step, to the face that needs
 *                the jump; 0.5 (the shared edge) when nothing needs a jump
 */
public record Step(StepKind kind, double rise, double riserAt) {
    public static Step none() {
        return new Step(StepKind.NONE, 0.0, 0.5);
    }
}
