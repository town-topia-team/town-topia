package com.alex.test.client;

/**
 * The knobs {@code /helditem} can turn.
 *
 * <p>Both a rig's held-item placement and a per-tool adjustment expose the same set, so
 * the command can build one set of nodes and point them at either. It lives in its own
 * file rather than nested inside {@link HeldItemTransform} because a class may not
 * implement an interface declared inside itself -- that is cyclic inheritance, and
 * javac says so.
 */
public interface Tunable {

    /** What this is called on the command line. */
    String name();

    /** Offsets in the limb's own frame: across the arm, up it from the fingertips, forward. */
    float offsetX();
    float offsetY();
    float offsetZ();

    /** Rotation in degrees, applied X then Y then Z. */
    float rotX();
    float rotY();
    float rotZ();

    float scale();

    void setOffset(float x, float y, float z);
    void addOffset(float x, float y, float z);
    void setRotation(float x, float y, float z);
    void addRotation(float x, float y, float z);
    void setScale(float scale);

    /** Back to the values compiled in. */
    void reset();

    /** One line, in the same argument order the command takes. */
    String describe();
}
