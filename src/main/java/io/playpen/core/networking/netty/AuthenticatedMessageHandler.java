package io.playpen.core.networking.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.playpen.core.Bootstrap;
import io.playpen.core.coordinator.PlayPen;
import io.playpen.core.protocol.Protocol;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class AuthenticatedMessageHandler extends SimpleChannelInboundHandler<Protocol.AuthenticatedMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Protocol.AuthenticatedMessage msg) throws Exception {
        if (msg.getVersion() != Bootstrap.getProtocolVersion()) {
            log.error("Protocol version mismatch! Expected " + Bootstrap.getProtocolVersion() + ", got "
                    + msg.getVersion());
            log.error("Disconnecting due to version mismatch.");
            ctx.channel().close();
            return;
        }

        if(!PlayPen.get().receive(msg, ctx.channel())) {
            log.error("Message failed");
            return;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Caught an exception while listening to a channel (closing connection)", cause);
        ctx.channel().close(); // closing the connection is fine, since a sane coordinator will just reconnect in a bit
    }
}
