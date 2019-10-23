package com.koushikdutta.scratch

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

class DiscardServerHandler : ChannelInboundHandlerAdapter() { // (1)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) { // (2)
        ctx.write(msg) // (1)
        ctx.flush() // (2)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        super.channelUnregistered(ctx)
        println(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) { // (4)
        // Close the connection when an exception is raised.
        cause.printStackTrace()
        ctx.close()
    }
}