package net.crulim.cobblekantoserverfixes;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Private proxy <-> backend control channel for the Cobblemon HOME cross-server bridge.
 *
 * The payload deliberately carries only a short control string. The player identity is
 * always taken from the network connection that carries the packet, so no selector or
 * arbitrary UUID can be supplied by a client.
 */
public record HomeBridgeControlPayload(String instruction) implements CustomPayload {
    public static final Id<HomeBridgeControlPayload> ID = new Id<>(
            Identifier.of(CobbleKantoServerFixes.MOD_ID, "home_bridge_control")
    );

    public static final PacketCodec<RegistryByteBuf, HomeBridgeControlPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING,
            HomeBridgeControlPayload::instruction,
            HomeBridgeControlPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
