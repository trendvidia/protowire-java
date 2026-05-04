package org.protowire.pxf;

import java.util.HashMap;
import java.util.Map;

/**
 * Plain {@code HashMap}-backed {@link PxfRegistry} with a fluent
 * {@code register(...)} builder. Suitable for tests, small applications,
 * and as the default the codegen tweak (queued) will populate at module
 * initialization. Larger or R8-sensitive deployments may prefer a
 * service-loader-based or annotation-processor-driven registry instead.
 *
 * <p>Not thread-safe for mutation; concurrent registration after the
 * registry is shared with worker threads is the caller's responsibility.
 * Reads are safe once mutation stops.
 */
public final class SimplePxfRegistry implements PxfRegistry {
    private final Map<String, PxfMeta> messages = new HashMap<>();
    private final Map<String, PxfEnum> enums = new HashMap<>();

    public SimplePxfRegistry register(PxfMeta meta) {
        messages.put(meta.fullName(), meta);
        return this;
    }

    public SimplePxfRegistry register(PxfEnum en) {
        enums.put(en.fullName(), en);
        return this;
    }

    @Override
    public PxfMeta lookupMessage(String fullName) {
        return messages.get(fullName);
    }

    @Override
    public PxfEnum lookupEnum(String fullName) {
        return enums.get(fullName);
    }
}
