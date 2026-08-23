package net.crulim.cobblekantoserverfixes;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class KantoNpcRivalStarterReflection {
    private static final String AUTO_DETECT_CLASS = "net.crulim.kantonpcs.rival.autodetect.RivalStarterAutoDetect";
    private static Method scanNowMethod;
    private static Method enqueueIfNeededMethod;

    private KantoNpcRivalStarterReflection() {
    }

    public static boolean enqueueIfNeeded(Object player, String reason) {
        if (player == null) {
            return false;
        }

        try {
            Method method = resolvePlayerMethod("enqueueIfNeeded", player, true);
            method.invoke(null, player);
            if (ServerFixesConfig.logRivalStarterScans) {
                CobbleKantoServerFixes.LOGGER.info("Queued KantoNPCs rival starter detection for {}. reason={}", player, reason);
            }
            return true;
        } catch (Exception exception) {
            if (ServerFixesConfig.logRivalStarterScans) {
                CobbleKantoServerFixes.LOGGER.warn("Failed to queue KantoNPCs rival starter detection for {}. reason={}", player, reason, exception);
            }
            return false;
        }
    }

    public static boolean scanNow(Object player, String reason) {
        if (player == null) {
            return false;
        }

        try {
            Method method = resolvePlayerMethod("scanNow", player, false);
            method.invoke(null, player);
            if (ServerFixesConfig.logRivalStarterScans) {
                CobbleKantoServerFixes.LOGGER.info("Forced KantoNPCs rival starter scan for {}. reason={}", player, reason);
            }
            return true;
        } catch (Exception exception) {
            if (ServerFixesConfig.logRivalStarterScans) {
                CobbleKantoServerFixes.LOGGER.warn("Failed to force KantoNPCs rival starter scan for {}. reason={}", player, reason, exception);
            }
            return false;
        }
    }

    private static Method resolvePlayerMethod(String name, Object player, boolean enqueue) throws Exception {
        Method cached = enqueue ? enqueueIfNeededMethod : scanNowMethod;
        if (cached != null) {
            return cached;
        }

        Class<?> autoDetectClass = Class.forName(AUTO_DETECT_CLASS);
        Class<?> playerClass = player.getClass();
        for (Method method : autoDetectClass.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isAssignableFrom(playerClass)) {
                if (enqueue) {
                    enqueueIfNeededMethod = method;
                } else {
                    scanNowMethod = method;
                }
                return method;
            }
        }

        throw new NoSuchMethodException(AUTO_DETECT_CLASS + "." + name + "(ServerPlayerEntity)");
    }
}
