package org.openmrs.module.stockmanagement.api.dispensing;

/** Package-private scope used only while the coordinating service saves a clinical record. */
final class AtomicDispensingScope implements AutoCloseable {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private AtomicDispensingScope() { ACTIVE.set(true); }
    static AtomicDispensingScope enter() {
        if (isActive()) { throw new IllegalStateException("Nested dispensing operation"); }
        return new AtomicDispensingScope();
    }
    static boolean isActive() { return Boolean.TRUE.equals(ACTIVE.get()); }
    @Override public void close() { ACTIVE.remove(); }
}
