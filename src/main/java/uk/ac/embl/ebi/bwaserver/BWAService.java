/*
 * Copyright 2018 asenf.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software3
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.embl.ebi.bwaserver;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import com.github.lindenb.jbwa.jni.BwaIndex;
import com.github.lindenb.jbwa.jni.BwaMem;
import java.io.File;
import java.io.IOException;

/**
 *
 * @author asenf
 */
public class BWAService {
    
    private static final boolean SSL = false;
    private static int port = 9221;

    private static Dailylog dailylog;
    
    // Shutdown process: Wait until current operations complete
    static volatile boolean keepRunning = true;

    // Executors
    private final DefaultEventExecutorGroup l, s;
    private final ScheduledExecutorService executor;
    
    // Index
    private BwaIndex index;
    private BwaMem mem;

    // Mask
    private MMapper mask;
    
    public BWAService(int port, int cores, String indexPath, String maskPath) {
        BWAService.port = port;

        // Executors
        this.l = new DefaultEventExecutorGroup(cores * 4);
        this.s = new DefaultEventExecutorGroup(cores * 4);
        
        // Traffic Shaping Handler already created
        this.executor = Executors.newScheduledThreadPool(cores);
        
        // Load/create index
        System.loadLibrary("bwajni");
        File indexFile = new File(indexPath);        
        try {
            System.out.println("Loading Index.");
            index = new BwaIndex(indexFile);
            mem = new BwaMem(index);
            System.out.println("Loading Index. Done.");
        } catch (IOException ex) {
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Check for existing Mask file
        long maskSize = 1024L*1024L*1024L*2L;  // 2GB - random default mask size
        File maskFile = new File(maskPath);
        if (maskFile.exists()) {
            System.out.println("Existing Mask File is specified.");
            maskSize = maskFile.length();
        }
        System.out.println("Declaring Mask File of size " + maskSize + " bytes.");
        try {            
            mask = new MMapper(maskPath, maskSize);
        } catch (Exception ex) {
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }
        System.out.println("Declaring Mask File. Done");        
    }
    
    public void run() throws Exception {
        
        EventLoopGroup acceptGroup = new NioEventLoopGroup();
        EventLoopGroup connectGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(acceptGroup, connectGroup)
             .channel(NioServerSocketChannel.class)
             //.handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new BWAServiceInitializer(this.l, this.s, this.mem, this.mask, this));

            Channel ch = b.bind(port).sync().channel();

            System.err.println("Open your web browser and navigate to " +
                    "http://127.0.0.1:" + port + '/');

            ch.closeFuture().sync();
        } finally {
            acceptGroup.shutdownGracefully();
            connectGroup.shutdownGracefully();            
            this.mem.dispose();
            this.index.close();
        }
    }
   
    public static void main(String[] args) {
        String p = "9221"; int pi = 9221;

        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                keepRunning = false;
                //try {
                //    mainThread.join();
                //} catch (InterruptedException ex) {;}

                System.out.println("Shutdown!!");
            }
        });

        int cores = Runtime.getRuntime().availableProcessors();
        
        String indexPath = null;
        String maskPath = null;

        Options options = new Options();

        options.addOption("p", true, "port");
        options.addOption("c", true, "cores");
        options.addOption("l", true, "index path");
        options.addOption("m", true, "mask path");
        
        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("p")) {
                p = cmd.getOptionValue("p");            
                pi = Integer.parseInt(p);
            }

            if (cmd.hasOption("c")) {
                String c = cmd.getOptionValue("c");
                cores = Integer.parseInt(c);
            }

            if (cmd.hasOption("l")) {
                indexPath = cmd.getOptionValue("l");
            }

            if (cmd.hasOption("m")) {
                maskPath = cmd.getOptionValue("m");
            } else {
                maskPath = System.getProperty("user.dir") + "/mask";
            }

        } catch (ParseException ex) {
            System.out.println("Unrecognized Parameter. Use '-c'  '-p'  '-l'  '-m'.");
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Start and run the server
        try {
            new BWAService(pi, cores, indexPath, maskPath).run();
        } catch (Exception ex) {
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
}
