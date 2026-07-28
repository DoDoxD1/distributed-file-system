package com.distributedfs.util;

import java.time.Instant;

/**
 * Time abstraction to keep core logic deterministic and testable.
 */
@FunctionalInterface
public interface TimeProvider {

    Instant now();
}
