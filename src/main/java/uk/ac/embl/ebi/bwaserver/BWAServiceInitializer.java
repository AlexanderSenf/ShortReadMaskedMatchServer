/*
 * Copyright 2018 asenf.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.embl.ebi.bwaserver;

import com.github.lindenb.jbwa.jni.BwaMem;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;

/**
 *
 * @author asenf
 */
public class BWAServiceInitializer  extends ChannelInitializer<SocketChannel> {
    private final DefaultEventExecutorGroup l, s, loc; // long and short lasting requests handled separately
    
    private final BWAService ref;
    
    private final BwaMem index;
    
    private final MMapper mask;
    
    public BWAServiceInitializer(DefaultEventExecutorGroup l, 
                                 DefaultEventExecutorGroup s,
                                 BwaMem index,
                                 MMapper mask,
                                 BWAService ref) {
        
        this.ref = ref;
        this.index = index;
        this.mask = mask;
        
        this.l = l;
        this.s = s;
        this.loc = new DefaultEventExecutorGroup(5);        
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {

        // On top of the SSL handler, add the text line codec.
        ch.pipeline().addLast(new HttpServerCodec());
        ch.pipeline().addLast(new HttpObjectAggregator(65536));
        ch.pipeline().addLast(new ChunkedWriteHandler());

        // and then business logic. This is CPU heavy - place in separate thread
        ch.pipeline().addLast(new BWAServiceHandler(this.index, this.mask));

    }
}
