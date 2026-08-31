package net.crulim.cobblekantoserverfixes;

import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Dedicated-server entrypoint.
 *
 * Keeps all pre-existing CobbleKanto Server Fixes initialization strictly on
 * the physical dedicated server while allowing this JAR to also contain
 * isolated client-only hotfixes.
 */
public final class CobbleKantoServerFixesDedicatedServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        new CobbleKantoServerFixes().onInitialize();
    }
}
