package com.example.reader.translate;

import java.util.function.Supplier;

public final class AdminTranslationContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private AdminTranslationContext() {
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }

    public static <T> T call(Supplier<T> supplier) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            return supplier.get();
        } finally {
            ACTIVE.set(previous);
        }
    }
}
