package net.crulim.cobblekantoserverfixes;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server -> proxy payload used only to request that the Velocity proxy moves
 * the player carrying this payload to another registered backend.
 *
 * The proxy validates the destination and consumes the packet before it can
 * reach the client.
 */
public record ServerSwitchPayload(String targetServer) implements CustomPayload {
    public static final Id<ServerSwitchPayload> ID = new Id<>(
            Identifier.of(CobbleKantoServerFixes.MOD_ID, "server_switch")
    );

    public static final PacketCodec<RegistryByteBuf, ServerSwitchPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING,
            ServerSwitchPayload::targetServer,
            ServerSwitchPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
