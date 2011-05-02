package org.nines;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class SolrClient {
    
    private String baseUrl;
    private Logger log;
    
    private static final int SOLR_REQUEST_NUM_RETRIES = 5; 
    private static final int SOLR_REQUEST_RETRY_INTERVAL = 30 * 1000; 
    public static final int HTTP_CLIENT_TIMEOUT = 2 * 60 * 1000; 
    
    public SolrClient(final String baseUrl) {
        
        this.baseUrl = baseUrl;
        this.log = Logger.getLogger(RDFIndexer.class.getName());
    }
    
    private HttpClient newHttpClient() {
        HttpClient httpClient = new HttpClient();
        httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(
            HTTP_CLIENT_TIMEOUT);
        httpClient.getHttpConnectionManager().getParams().setIntParameter(
            HttpMethodParams.BUFFER_WARN_TRIGGER_LIMIT, 10000 * 1024); 
        return httpClient;
    }
    
    /**
     * Check if core exists. Create it if it does not
     * @param name
     */
    public void validateCore( final String core ) throws IOException {

        GetMethod request = null;
        try {
            request = new GetMethod( this.baseUrl+"/admin/cores?action=STATUS");
            execRequest( request );
            String response = getResponseString( request );
            int exists = response.indexOf(">" + core + "<");
            if (exists <= 0) {
                // The core doesn't exist: create it.
                request = new GetMethod(this.baseUrl+"/admin/cores?action=CREATE&name=" 
                    + core + "&instanceDir=.");
                execRequest( request );
                this.log.info(">>>> Created core: " + core);
            }
        } catch (IOException e ){
            throw e;
        } finally {
            if ( request != null ) {
                request.releaseConnection();
            }
        }
    }
    
    /**
     * Get the full documeny text from the specifed URI and core name
     * @param uri
     * @return
     * @throws IOException
     */
    public final String getFullText( final String uri, final String core ) throws IOException {
        GetMethod get = null;
        try {
            String solrUrl = this.baseUrl + core + "/select";
            get = new GetMethod(solrUrl);
            NameValuePair queryParam = new NameValuePair("q", "uri:\"" + uri + "\"");
            NameValuePair fieldsParam = new NameValuePair("fl", "text");
            NameValuePair params[] = new NameValuePair[] { queryParam, fieldsParam };
            get.setQueryString(params);
            execRequest(get);
            return getResponseString(get);
        } catch (IOException e ){
            throw e;
        } finally {
            if ( get != null ) {
                get.releaseConnection();
            }
        }
    }
    
    private final void execRequest( HttpMethod request ) throws IOException {
        
        HttpClient httpClient = newHttpClient();
        int responseCode;
        int solrRequestNumRetries = SOLR_REQUEST_NUM_RETRIES;
        do {
            responseCode = httpClient.executeMethod(request);
            if (responseCode != 200) {
                try {
                    Thread.sleep(SOLR_REQUEST_RETRY_INTERVAL);
                    log.info(">>>> SOLR request "+request.getQueryString()+" FAILED : " 
                        + responseCode + " (retrying...)");
                } catch (InterruptedException e) {}
            } else {
                if (solrRequestNumRetries != SOLR_REQUEST_NUM_RETRIES) {
                    log.info(">>>> SOLR request "+request.getQueryString()+":  (succeeded!)");
                }
            }
            solrRequestNumRetries--;
        } while (responseCode != 200 && solrRequestNumRetries > 0);
        
        if (responseCode != 200) {
            throw new IOException("Non-OK response: " + responseCode + "\n\n" + request.getQueryString());
        }
    }
    
    private final String getResponseString(HttpMethod httpMethod) throws IOException {
        InputStream is = httpMethod.getResponseBodyAsStream();
        return IOUtils.toString(is, "UTF-8");
    }
    
    public final List<JsonObject> getResultsPage( final String core, final String archive,
        final int page, final int pageSize, final String fields)  {

        ArrayList<JsonObject> result = new ArrayList<JsonObject>();
        GetMethod get = null;
        
        // build the request query string
        try {
            String a = URLEncoder.encode("\"" + archive + "\"", "UTF-8");
            String query = this.baseUrl + "/" + core + "/select/?q=archive:" + a;
            query = query + "&start=" + (page * pageSize) + "&rows=" + pageSize;
            query = query + "&fl=" + fields;
            query = query + "&sort=uri+asc";
            query = query + "&wt=json";
            get = new GetMethod(query);
        } catch (UnsupportedEncodingException e) {
            this.log.error("Unable to create solr requst query", e);
            return result;
        }

        // execute the query
        try {
            execRequest(get);
        } catch (IOException e) {
            this.log.error("Solr request failed", e);
            get.releaseConnection();
            return result;
        }

        // read the result into an array of JSON objects
        try  {
            JsonParser parser = new JsonParser();
            JsonElement parsed = parser.parse(new InputStreamReader(get.getResponseBodyAsStream()));
            JsonObject data = parsed.getAsJsonObject();
            JsonObject re = data.get("response").getAsJsonObject();
            JsonElement de = re.get("docs");
            JsonArray docs = de.getAsJsonArray();
            Iterator<JsonElement> i = docs.iterator();
            while (i.hasNext()) {
                result.add(i.next().getAsJsonObject());
            }
        } catch (IOException e ) {
            this.log.error("Unable to read solr response", e);
        }

        get.releaseConnection();
        return result;
        
    }
    
    /**
     * Post the XML payload to the specified SOLR archive
     * 
     * @param xml
     * @param archive
     * @throws IOException
     */
    public void post(String xml, String archive) throws IOException {

        PostMethod post = new PostMethod(this.baseUrl + "/" + archive + "/update");
        post.setRequestEntity(new StringRequestEntity(xml, "text/xml", "utf-8"));
        post.setRequestHeader("Content-type", "text/xml; charset=utf-8");

        // Execute request
        try {
            execRequest(post);
            String response = getResponseString(post);
            Pattern pattern = Pattern.compile("status=\\\"(\\d*)\\\">(.*)\\<\\/result\\>", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(response);
            while (matcher.find()) {
                String status = matcher.group(1);
                String message = matcher.group(2);
                if (!"0".equals(status)) {
                    throw new IOException(message);
                }
            }
        } finally {
            // Release current connection to the connection pool once you are done
            post.releaseConnection();
        }
    }

}
