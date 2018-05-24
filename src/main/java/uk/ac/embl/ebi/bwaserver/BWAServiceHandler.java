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

/*
 * This class provides responses to REST URLs
 *
 * URL Prefix for his server is: /v1
 *
 * Resources are:
 *
 * /proc?seq=[...]              Get yes/no answer (proceed to sequence, or not)
 *
 * POST /mask/                  TODO
 * 
 * /stats/load                  TODO Get CPU load of Server
 * 
 */

package uk.ac.embl.ebi.bwaserver;

import com.github.lindenb.jbwa.jni.AlnRgn;
import com.github.lindenb.jbwa.jni.BwaMem;
import com.github.lindenb.jbwa.jni.ShortRead;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.util.CharsetUtil;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.unbescape.html.HtmlEscape;

/**
 *
 * @author asenf
 */
public class BWAServiceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final BwaMem mem;           // Alternative - simply refer to base object!   
    private final MMapper mask;
    
    public BWAServiceHandler(BwaMem index, MMapper mask) {
        super();
        this.mem = index;
        this.mask = mask;        
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        
        // Step 1: Response Type
        //String get = request.headers().get("Accept").toString(); // Response Type
        String get = "";
/*
         // Active?
        if (!EgaSecureConfigLogService.keepRunning) {
            sendError(ctx, GONE, get);
            return;
        }
        // Step 1: If server load too high, reject connection
        if (!active || load > load_ceiling) { // This server doesn't really produce much load
            System.out.println("load=" + load); // DEVELOPMENT
            sendError(ctx, TOO_MANY_REQUESTS, get);
            return;
        }
        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, BAD_REQUEST, get);
            return;
        }        
*/        

        // Step 2: Sanitize and process URL
        // Sanitize provided URL based on OWASP guidelines
        PolicyFactory policy = Sanitizers.FORMATTING.and(Sanitizers.LINKS);
        String safeUri = policy.sanitize(request.uri()); // take directly as provided by client, and sanitize it
        String unescapedSafeUri = HtmlEscape.unescapeHtml(safeUri);

        // Process URL: get request parameters
        String newUrl = unescapedSafeUri.startsWith("http")?unescapedSafeUri:("http://" + unescapedSafeUri);
        URL user_action = new URL(newUrl);
        String path = user_action.getPath();
        Map<String, String> parameters = new LinkedHashMap<>();
        String[] pairs = user_action.getQuery()!=null?user_action.getQuery().split("&"):null;
        if (pairs!=null) for (String pair : pairs) {
            int idx = pair.indexOf("=");
            parameters.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
        }

        // Process URL: get request path
        String function = "";
        ArrayList<String> id = new ArrayList<>();
        try {
            StringTokenizer token = new StringTokenizer(path, "/");
            String t = token.nextToken();
            
            // v1 / proc ? seq=[...]
            assert(t.equalsIgnoreCase("v1"));
            t = token.nextToken();
            assert(t.equalsIgnoreCase("proc"));
            
        } catch (Throwable t) {
            sendError(ctx, BAD_REQUEST, get); // If the URL is incorrect...
            return;
        }
        // Step 3: Perform Workload (this is why this server exists..)
        JSONObject json = null;
        try {
            json = new JSONObject();
            
            // Query Sequence --> Short Read
            String seq = parameters.get("seq");
            byte[] qual = new byte[seq.length()];
            Arrays.fill(qual, (byte)20);
            //ShortRead read = new ShortRead("query", seq.getBytes(), new byte[seq.length()]);
            ShortRead read = new ShortRead("query", seq.getBytes(), qual);

            // Alignment(s)
            AlnRgn[] align = mem.align(read);
            
            if (align.length > 0) {
                long position = align[0].getPos();

                int maskPos = (int) (position / 2); // Assuming 1/2 byte per position
                int offset = (int) (position % 2);

                byte[] val = new byte[1];
                this.mask.getBytes(position, val);

                int high_bits = val[0]>>4;
                int low_bits = val[0]&15;

                json.append("Pos", position);
                json.append("MaskVal", (offset==0?high_bits:low_bits));
            } else {
                json.append("Error", "No Alignment Found.");                
            }
            // DEMO: Return alignment
            //for (int i=0; i<align.length; i++) {
            //    json.append("Align_".concat(String.valueOf(i)), align[i].toString());
            //}
            
        } catch (Throwable th) {
            sendError(ctx, BAD_REQUEST, get); // If the URL Function is incorrect...
            return;
        }
        
        // Step 4: Prepare a response - set content typt to the expected type
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        StringBuilder buf = new StringBuilder();
        response.headers().set(CONTENT_TYPE, "application/json");
        buf.append(json.toString());
        
        // Step 5: Result has been obtained. Build response and send to requestor
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        
        // Cleanup
        buffer.release();
    }

    // -------------------------------------------------------------------------
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (ctx.channel().isActive()) {
            sendError(ctx, INTERNAL_SERVER_ERROR);
        }
    }
    
    // Set Response ype
    private static void setContentTypeHeaderBinary(HttpResponse response) {
        response.headers().set(CONTENT_TYPE, "application/octet-stream");
    }
    
    // Generate JSON Header Section
    private JSONObject responseHeader(HttpResponseStatus status) throws JSONException {
        return responseHeader(status, "");
    }
    private JSONObject responseHeader(HttpResponseStatus status, String error) throws JSONException {
        JSONObject head = new JSONObject();
        
        head.put("apiVersion", "v1");
        head.put("code", String.valueOf(status.code()));
        head.put("errorStack", error);                     // TODO ??
        
        return head;
    }

    // Generate and send Error Message
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        sendError(ctx, status, "application/json");
    }
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String get) {
        //BWAService.log(status.toString());
        try {
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
            JSONObject json = new JSONObject(); // Start out with common JSON Object
            json.put("header", responseHeader(status)); // Header Section of the response
            json.put("response", "null"); // ??
            
            StringBuilder buf = new StringBuilder();
            if (get.contains("application/json")) { // Format list of values as JSON
                response.headers().set(CONTENT_TYPE, "application/json");
                buf.append(json.toString());
            } else if (get.contains("xml")) { // Format list of values as XML
                response.headers().set(CONTENT_TYPE, "application/xml");
                String xml = XML.toString(json);
                buf.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                buf.append("<Result>");
                buf.append(xml);
                buf.append("</Result>");
            }
            
            ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
            response.content().writeBytes(buffer);
            
            // Close the connection as soon as the error message is sent.
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (JSONException ex) {
            Logger.getLogger(BWAServiceHandler.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}
