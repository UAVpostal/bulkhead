package app.bulkhead;

/**
 * One activity class per process slot.
 *
 * Android fixes an activity's process in the manifest, so the app declares
 * eight identical browser activities, each pinned to its own process
 * (:c0 to :c7). A compartment is assigned a free slot when it opens.
 */
public final class Slot {
    private Slot() {}

    public static final class S0 extends BrowserActivity {}
    public static final class S1 extends BrowserActivity {}
    public static final class S2 extends BrowserActivity {}
    public static final class S3 extends BrowserActivity {}
    public static final class S4 extends BrowserActivity {}
    public static final class S5 extends BrowserActivity {}
    public static final class S6 extends BrowserActivity {}
    public static final class S7 extends BrowserActivity {}
}
