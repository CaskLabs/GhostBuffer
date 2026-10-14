package com.casklabs.ghostbuffer.time;

import com.github.ma1co.openmemories.framework.DateTime;

/**
 * Clock provider yielding monotonic uptime timestamps across both Android Dalvik
 * runtime and local JVM host environments (unit tests).
 */
public class GhostTime {
    /**
     * Monotonic uptime in milliseconds, safe from wall-clock jumps and host JVM friendly.
     */
    public static long uptimeMillis() {
        return System.nanoTime() / 1000000L;
    }

    /**
     * Wall-clock time in milliseconds (for timestamps and human-readable filenames).
     */
    public static long currentTimeMillis() {
        try {
            // Sony's Android system clock starts at 1970; TimeUtil provides camera time.
            return DateTime.getInstance().getCurrentTime().getTimeInMillis();
        } catch (LinkageError e) {
            // Sony/Android classes are unavailable in local JVM tests.
            return System.currentTimeMillis();
        }
    }
}
