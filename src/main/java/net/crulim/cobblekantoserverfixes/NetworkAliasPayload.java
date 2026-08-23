package net.crulim.cobblekantoserverfixes;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Proxy -> backend alias instruction for the player carrying this payload. */
public record NetworkAliasPayload(String instruction) implements CustomPayload {
    public static final Id<NetworkAliasPayload> ID = new Id<>(
            Identifier.of(CobbleKantoServerFixes.MOD_ID, "network_alias")
    );

    public static final PacketCodec<RegistryByteBuf, NetworkAliasPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING,
            NetworkAliasPayload::instruction,
            NetworkAliasPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
