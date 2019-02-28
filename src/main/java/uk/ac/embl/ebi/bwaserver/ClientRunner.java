/*
 * Copyright 2019 asenf.
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

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author asenf
 */
public class ClientRunner implements Runnable {

    private CloseableHttpClient client;
    private CloseableHttpResponse response = null;
    private String sUrl;
    private int mode;
    
    private int fhb, flb;

    private boolean OK;
    
    public ClientRunner(CloseableHttpClient client, String sUrl, 
                        int mode, int testMaskEntry) {
        this.client = client;
        this.sUrl = sUrl;
        this.mode = mode;
        
        if (mode == 0) {
            this.fhb = testMaskEntry>>4;
            this.flb = testMaskEntry&15;
        } else if (mode == 1) {
            
        }
        
        this.OK = true;
    }
    
    @Override
    public void run() {
        HttpUriRequest request = new HttpGet(this.sUrl);
        try {
            response = client.execute(request);
            String json = EntityUtils.toString(response.getEntity(), "UTF-8");
            HashMap map = jsonToMap(json);
            
            // Compare REST call with direct File access
            if (mode == 0) {
                if (flb != Integer.parseInt(map.get("MaskValForward").toString()) || 
                    fhb != Integer.parseInt(map.get("MaskValReverse").toString())) {
                    this.OK = false;
                    
                    System.out.println("ERROR in MASK File Processing");
                    System.out.println("** file_l_b" + flb + " =?= " + map.get("MaskValForward"));
                    System.out.println("** file_h_b" + fhb + " =?= " + map.get("MaskValReverse"));                    
                }
            } else if (mode == 1) {
                // Nothing yet... just interested in the time per call atm
            }

            response.close();            
        } catch (IOException ex) {
            Logger.getLogger(ClientRunner.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public boolean getStatus() {
        return this.OK;
    }
    
    private HashMap jsonToMap(String t) throws JSONException {

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
