package org.huntercore.fakeplayer;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

final class HunterFakeConnection extends Connection {

    HunterFakeConnection() {
        super(PacketFlow.SERVERBOUND);
        final EmbeddedChannel fakeChannel = new EmbeddedChannel();
        final ChannelPipeline pipeline = fakeChannel.pipeline();
        Connection.configureInMemoryPipeline(pipeline, PacketFlow.SERVERBOUND);
        if (pipeline.get(HandlerNames.ENCODER) == null) {
            pipeline.addLast(HandlerNames.ENCODER, new ChannelOutboundHandlerAdapter());
        }
        this.configurePacketHandler(pipeline);
        this.channel = fakeChannel;
        final InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        this.address = loopback;
        this.virtualHost = loopback;
        this.hostname = "huntercore-fake-player";
        this.preparing = false;
        this.isPending = false;
    }

    @Override
    public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener, final boolean flush) {
        final ServerPlayer player = this.getPlayer();
        packet.onPacketDispatch(player);
        if (packet.hasFinishListener()) {
            packet.onPacketDispatchFinish(player, null);
        }
        if (listener != null && this.channel != null) {
            try {
                final ChannelFuture future = this.channel.newSucceededFuture();
                listener.operationComplete(future);
            } catch (final Exception ex) {
                throw new RuntimeException("Fake player packet listener failed", ex);
            }
        }
    }

    @Override
    public void flushChannel() {
    }
}
