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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.stream.LongStream;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

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

    // Test Mode Indocator
    private boolean testMe = false;
    private String indexPath;
    
    public BWAService(int port, int cores, String indexPath, String maskPath, 
                      boolean verbose, boolean testmode) throws IOException {
        BWAService.port = port;

        // Executors
        this.l = new DefaultEventExecutorGroup(cores * 4);
        this.s = new DefaultEventExecutorGroup(cores * 4);
        
        // Traffic Shaping Handler already created
        this.executor = Executors.newScheduledThreadPool(cores);
        
        // Load/create index
        System.loadLibrary("bwajni");
        if (verbose) System.out.println("System.loadLibrary OK");
        if (verbose) System.out.println("Loading Index " + indexPath);
        this.indexPath = indexPath;
        File indexFile = new File(indexPath);        
        try {
            if (verbose) System.out.println("Loading Index.");
            index = new BwaIndex(indexFile);
            mem = new BwaMem(index);
            if (verbose) System.out.println("Loading Index. Done.");
        } catch (IOException ex) {
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Check for existing Mask file
        long maskSize = 1024L*1024L*1024L*2L;  // 2GB - random default mask size
        if (verbose) System.out.println("Mask File");
        File maskFile = new File(maskPath);
        if (verbose) System.out.println("Mask Path " + maskFile.getCanonicalPath());
        if (maskFile.exists()) {
            System.out.println("Existing Mask File is specified.");
            maskSize = maskFile.length();
        }
        System.out.println("Using Mask File of size " + maskSize + " bytes.");
        try {            
            mask = new MMapper(maskPath, maskSize);
        } catch (Exception ex) {
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }
        System.out.println("Setting up Mask File. Done");        
        
        this.testMe = testmode;
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

            if (testMe)
                testMe();
            
            ch.closeFuture().sync();
        } finally {
            acceptGroup.shutdownGracefully();
            connectGroup.shutdownGracefully();            
            this.mem.dispose();
            this.index.close();
        }
    }
   
    public static void main(String[] args) throws IOException {
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
        boolean verbose = false;
        boolean testmode = false;

        Options options = new Options();

        options.addOption("p", true, "port");
        options.addOption("c", true, "cores");
        options.addOption("l", true, "index path");
        options.addOption("m", true, "mask path");
        options.addOption("v", false, "verbose");
        options.addOption("t", false, "test");
        
        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("v")) {
                verbose = true;
                System.out.println("Verbose mode selected.");
            }            
            if (cmd.hasOption("t")) {
                testmode = true;
                System.out.println("Test mode selected.");
            }            
            
            if (cmd.hasOption("p")) {
                p = cmd.getOptionValue("p");            
                pi = Integer.parseInt(p);
            }
            if (verbose) System.out.println("Port: " + pi);

            if (cmd.hasOption("c")) {
                String c = cmd.getOptionValue("c");
                cores = Integer.parseInt(c);
            }
            if (verbose) System.out.println("Number of cores: " + cores);

            if (cmd.hasOption("l")) {
                indexPath = cmd.getOptionValue("l");
            }
            if (verbose) {
                System.out.println("Path to Index file: " + indexPath);
                System.out.println("first 10 files in " + (indexPath.substring(0, indexPath.lastIndexOf("/"))) + ":");
                Files.list(new File(indexPath.substring(0, indexPath.lastIndexOf("/"))).toPath())
                    .limit(10)
                    .forEach(path -> {
                        System.out.println(path);
                    });
            }

            if (cmd.hasOption("m")) {
                maskPath = cmd.getOptionValue("m");
            } else {
                maskPath = System.getProperty("user.dir") + "/mask";
            }
            if (verbose) {
                System.out.println("Path to Mask file: " + maskPath);
                System.out.println("first 10 files in " + (maskPath.substring(0, maskPath.lastIndexOf("/"))) + ":");
                Files.list(new File(maskPath.substring(0, maskPath.lastIndexOf("/"))).toPath())
                    .limit(10)
                    .forEach(path -> {
                        System.out.println(path);
                    });
            }

        } catch (ParseException ex) {
            System.out.println("Unrecognized Parameter. Use '-c'  '-p'  '-l'  '-m'  '-v'.");
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Start and run the server
        try {
            new BWAService(pi, cores, indexPath, maskPath, verbose, testmode).run();
        } catch (Exception ex) {
            Logger.getLogger(BWAService.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
 
    // Test Mode -- test mask, query
    private void testMe() throws InterruptedException, FileNotFoundException, IOException {
        // Wait until server has started up
        Thread.sleep(2000);
        
        // Obtain random mask file entries - test query settings
        RandomAccessFile rafM = new RandomAccessFile(this.mask.getLoc(), "r");
        long lenM = rafM.length();
        int numMaskEntries = 100000;
        System.out.println("Reading " + numMaskEntries + " random entries from mask file.");
        byte[] testMaskEntries = new byte[numMaskEntries];
        long[] testMaskIndices = new long[numMaskEntries];
        Random r = new Random(lenM);
        LongStream longsM = r.longs(numMaskEntries, 0, lenM).sorted();
        testMaskIndices = longsM.toArray();
        for (int i=0; i<numMaskEntries; i++) {
            if (testMaskIndices[i] > lenM)
                testMaskIndices[i] = (lenM-1<0)?0:lenM-1;
            rafM.seek(testMaskIndices[i]);
            testMaskEntries[i] = rafM.readByte();
        }
        rafM.close();
        System.out.println("Reading " + numMaskEntries + " random entries from mask file. Done");
        
        // Obtain random Index entries
        RandomAccessFile rafI = new RandomAccessFile(this.indexPath, "r");
        long lenI = rafI.length();
        int numIndexEntries = 100000;
        int seqLen = 200;
        System.out.println("Reading " + numIndexEntries + " random entries from Index FASTA file.");
        String[] testIndexEntries = new String[numIndexEntries];
        r = new Random(lenI);
        LongStream longsI = r.longs(numIndexEntries, 0, lenI).sorted();
        long[] toArray = longsI.toArray();
        for (int i=0; i<numIndexEntries; i++) {
            if (toArray[i] > (lenI-seqLen))
                toArray[i] = (lenI-seqLen-1<0)?0:lenI-seqLen-1;
            //if (toArray[i] < 100)
            //    toArray[i] = 100;
            rafI.seek(toArray[i]);
            byte[] bTmp = new byte[seqLen];
            rafI.readFully(bTmp);
            testIndexEntries[i] = (new String(bTmp)).replaceAll("\n", "");
            testIndexEntries[i].replaceAll("[^ACTGN]", "");
            //System.out.println("Seq " + testIndexEntries[i]);
        }
        rafI.close();
        System.out.println("Reading " + numIndexEntries + " random entries from Index FASTA file. Done");

        // Run Tests
        CloseableHttpClient client = HttpClientBuilder.create().build();
        CloseableHttpResponse response = null;
        long maskTotal = 0, maskTemp = 0;
        for (int i=0; i<numMaskEntries; i++) {
            HttpUriRequest request = new HttpGet("http://localhost:9221/v1/mask?pos=" + testMaskIndices[i]);
            maskTemp = System.currentTimeMillis();
            response = client.execute(request);
            maskTotal += (System.currentTimeMillis() - maskTemp);
            String json = EntityUtils.toString(response.getEntity(), "UTF-8");
            HashMap map = jsonToMap(json);
            
            // Compare REST call with direct File access 
            int fhb = testMaskEntries[i]>>4;
            int flb = testMaskEntries[i]&15;
            
            if (flb != Integer.parseInt(map.get("MaskValForward").toString()) || 
                fhb != Integer.parseInt(map.get("MaskValReverse").toString())) {
                System.out.println("ERROR in MASK File Processing");
                System.out.println("** file_l_b" + flb + " =?= " + map.get("MaskValForward"));
                System.out.println("** file_h_b" + fhb + " =?= " + map.get("MaskValReverse"));
                System.exit(99);
            }

            response.close();
        }
        System.out.println("Avg Mask Call: " + (double)maskTotal/(double)numMaskEntries + " milliseconds.");
        
        // Match Test
        long indexTotal = 0, indexTemp = 0;
        for (int i=0; i<numMaskEntries; i++) {
            HttpUriRequest request = new HttpGet("http://localhost:9221/v1/proc?seq=" + testIndexEntries[i]);
            indexTemp = System.currentTimeMillis();
            response = client.execute(request);
            indexTotal += (System.currentTimeMillis() - indexTemp);
            String json = EntityUtils.toString(response.getEntity(), "UTF-8");
            HashMap map = jsonToMap(json);

            //System.out.println(map.toString());

            response.close();
        }        
        System.out.println("Avg Index Call: " + (double)indexTotal/(double)numIndexEntries + " milliseconds.");
        
        // Done - End Server
        System.exit(100);
    }

    private static HashMap jsonToMap(String t) throws JSONException {

        HashMap<String, String> map = new HashMap<>();
        JSONObject jObject = new JSONObject(t);
        Iterator<?> keys = jObject.keys();

        while( keys.hasNext() ){
            String key = (String)keys.next();
            String value = jObject.get(key).toString().substring(1, jObject.get(key).toString().length()-1 );
            map.put(key, value);
        }

        return map;
    }
}
