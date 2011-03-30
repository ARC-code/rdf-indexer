package org.nines;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.util.JavaBinCodec;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.nines.RDFIndexerConfig.CompareMode;

/**
 * RDF Compare will perform comparisions on the target arcive and the main SOLR index.
 * 
 * @author loufoster
 *
 */
public class RDFCompare {
  
  private RDFIndexerConfig config;
  private boolean includesText = false;
  private Logger log;
  private PrintStream sysOut;
  private HttpClient httpClient;
  private LinkedHashMap<String,List<String>> errors = new LinkedHashMap<String,List<String>>();
  private int errorCount = 0;
  
  // all of the solr instance fields. Text is the last field
  private static final ArrayList<String> NON_TEXT_FIELDS = new ArrayList<String>( Arrays.asList(
    "uri", "archive", "date_label", "genre", "source", "image", "thumbnail", "title", 
    "alternative", "url", "role_ART", "role_AUT", "role_EDT", "role_PBL", "role_TRL", 
    "role_EGR", "role_ETR", "role_CRE", "freeculture", "is_ocr", "federation", 
    "has_full_text", "source_xml", "typewright", "publisher", "agent", "agent_facet", 
    "author", "batch", "editor", "text_url", "year", "type", "date_updated", "title_sort", 
    "author_sort", "year_sort", "source_html", "source_sgml", "person", "format", 
    "language", "geospacial"));

  private static final ArrayList<String> LARGE_TEXT_ARCHIVES = new ArrayList<String>( Arrays.asList(
      "PQCh-EAF", "amdeveryday", "oldBailey" ));
  
  private static final ArrayList<String> REQUIRED_FIELDS = new ArrayList<String>( Arrays.asList(
      "title_sort", "title", "genre", "archive", "url", 
      "federation", "year_sort", "freeculture", "is_ocr"));
  
  // Static connecton config
  private static final int SOLR_REQUEST_NUM_RETRIES = 5; // how many times we should try to connect with solr before giving up
  private static final int SOLR_REQUEST_RETRY_INTERVAL = 30 * 1000; // milliseconds
  private static final int HTTP_CLIENT_TIMEOUT = 2*60*1000; // 2 minutes


  /**
   * Construct an instance of the RDFCompare with the specified config
   * @param config
   * @throws IOException 
   */
  public RDFCompare(RDFIndexerConfig config) throws IOException {
    this.config = config;
    
    // init logging
    String logFileName = config.archiveName;
    logFileName = logFileName.replaceAll("/", "_").replaceAll(":", "_").replaceAll(" ", "_");
    String logFileRelativePath = "../../../log/";
    String logPath = logFileRelativePath+logFileName + "_compare_"+ config.compareMode+".log";
    FileAppender fa = new FileAppender(new PatternLayout("%m\n"), logPath.toLowerCase());
    fa.setEncoding("UTF-8");
    BasicConfigurator.configure( fa );
    this.log = Logger.getLogger(RDFIndexer.class.getName());
    
    // set up sys out so it can handle utf-8 output
    try {
        this.sysOut = new PrintStream(System.out, true, "UTF-8");
    } catch (UnsupportedEncodingException e) {
        this.sysOut = null;
    }
    
    // init the solr connection
    this.httpClient = new HttpClient();
    this.httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(
        HTTP_CLIENT_TIMEOUT);
    this.httpClient.getHttpConnectionManager().getParams().setIntParameter(
        HttpMethodParams.BUFFER_WARN_TRIGGER_LIMIT, 10000 * 1024);
  }


  /**
   * Perform the comparison based on the config passed into the c'tor
   */
  public void compareArhive() {

    // log start time
    Date start = new Date();
    this.log.info("Started compare at " + start);
    logInfo("====== Scanning archive \"" + config.archiveName + "\" ====== ");
    
    String fl = getFieldList();
    
    // get docs from current index
    List<SolrDocument> indexDocs = getSolrDocuments(
        "resources", config.archiveName, fl);
  
    // get docs from reindex archive
    List<SolrDocument> archiveDocs = getSolrDocuments(archiveToCoreName(
        config.archiveName), config.archiveName, fl);
    
    if ( this.config.compareMode.equals(CompareMode.TEXT) == false) {
      logInfo("retrieved "+archiveDocs.size()+" new rdf objects;");
      logInfo("retrieved " + indexDocs.size() +" old objects;");
    }
    
    // do the comparison
    compareLists(indexDocs, archiveDocs);
    
    for (Map.Entry<String, List<String>> entry: this.errors.entrySet()) {
      String uri = entry.getKey();
      if ( uri.equals("txt")) {
        for (String msg : entry.getValue() ) {
          logInfo(msg);
        }
      } else {
        logInfo("---"+uri+"---");
        for (String msg : entry.getValue() ) {
          logInfo("    "+msg);
        }
      }
    }
    
    // done log some stats
    this.log.info("Total Docs Scanned: "+archiveDocs.size()+". Total Errors: "+this.errorCount+".");
    
    Date end = new Date();
    double durationSec = (end.getTime()-start.getTime())/1000.0;
    if (durationSec >= 60 ) {
      logInfo( String.format("Finished in %3.2f minutes.", (durationSec/60.0)));
    } else {
      logInfo( String.format("Finished in %3.2f seconds.", durationSec));
    }
  }

  /**
   * Look at the compare config and generate a field list
   * suitable for submission to Solr: 
   * @return List in the form: field1+field2+...
   */
  private String getFieldList() {
    
    this.includesText = true;
    if ( this.config.compareMode.equals(CompareMode.FAST)) {
      this.includesText = false;
      StringBuilder sb = new StringBuilder();
      for (String s : NON_TEXT_FIELDS) {
        if (sb.length()>0) {
          sb.append("+");
        }
        sb.append(s);
      }
      return sb.toString();
    } else if ( this.config.compareMode.equals(CompareMode.TEXT)) {
      return "uri+is_ocr+has_full_text+text";
    }
    return "*";
  }
  
  /**
   * Scan thru each document in the archive and find differences 
   * @param indexDocs List of all original docs in the index
   * @param archiveDocs List of docs in the reindexed archive
   * @throws Exception
   */
  private void compareLists(List<SolrDocument> indexDocs, List<SolrDocument> archiveDocs) {
    
    // hash the indexed docs by uri to hopefull speed stuff up
    HashMap<String, SolrDocument> indexed = new HashMap<String, SolrDocument>();
    for ( SolrDocument doc : indexDocs) {
      String uri = doc.get("uri").toString();
      indexed.put(uri, doc);
    }
    indexDocs.clear();
    
    // Run thru al items in new archive. Validate correct data
    // and compare against object in original index if possible
    for ( SolrDocument doc : archiveDocs) {

      // look up the corresponding object in the original index
      String uri = doc.get("uri").toString();
      SolrDocument indexDoc = indexed.get(uri);
      
      // On full compares, validaate all required
      // fields are present and contain content
      if ( this.config.compareMode.equals(CompareMode.FULL)) {
        validateRequiredFields(doc);
      }
      
      // If this doc is not a new one, diff it with the original
      if ( indexDoc != null) {
        compareFields( uri, indexDoc, doc);
      }
    }
  }

  /**
   * Walk through each field in the new doc and compare it with the
   * old. Log any differences. 
   * @param uri
   * @param indexDoc
   * @param doc
   */
  private void compareFields(String uri, SolrDocument indexDoc, SolrDocument doc) {
    
    // loop over all keys in doc
    for (Entry<String, Object> entry: doc.entrySet()) {
      
      // get key and do special handing for text fields
      String key = entry.getKey();
      if ( key.equals("text")) {
        compareText(uri, indexDoc, doc);
        continue;
      }
      
      // grab new val
      String newVal = toSolrString(entry.getValue());
      
      // is this a new key?
      if ( indexDoc.containsKey(key) == false) {
        if (isIgnoredNewField(key) == false) {
          addError( uri, key+" "+newVal.replaceAll("\n", " / ")+" introduced in reindexing.");
        } 
        continue;
      }
      
      // get parallel val in indexDoc
      String oldVal = toSolrString(indexDoc.get(key));
      
      // dump the key from indexDoc so we can detect
      // unindexed values later
      indexDoc.remove(key);
      
      // don't compare batch or score
      if ( key.equals("batch") || key.equals("score") ) {        
        continue;
      }
     
      // difference?
      if ( newVal.equals(oldVal) == false) {
        
        // make sure everything is escaped and check again.
        String escapedOrig = getProcessedOrigField(oldVal);
        String escapedNew = getProcessedReindexedField(newVal);
        if ( escapedNew.equals(escapedOrig) == false ) {
          
          // too long to dump in a single error line?
          if (oldVal.length() > 30) {
          
            // log a summary
            addError(uri, key
                + " mismatched: length= " + newVal.length()+" (new)"
                + " vs. "+oldVal.length()+" (old)");
            
            // then find first diff and log it
            String[] oldArray = oldVal.split("\n");
            String[] newArray = newVal.split("\n");
            for ( int i=0; i<= oldArray.length; i++ ) {
              if ( oldArray[i].equals(newArray[i]) == false) {
               
                addError(uri, "        at line "+i+":\n"
                    + "\"" + newArray[i].replaceAll("\n", " / ") + "\" vs.\n" 
                    + "\"" + oldArray[i].replaceAll("\n", " / ") + "\"");
                break;
              }
            }

          } else {
            
            // dump the entire diff to the log
            addError(uri, key
                + " mismatched: \"" + newVal.replaceAll("\n", " / ") + "\" (new)" 
                + " vs. \"" + oldVal.replaceAll("\n", " / ") + "\" (old)");
          }

        }
      }
    }
    
    // now see if there are any leftover fields in indexDoc
    // log them is not reindexed
    for (Entry<String, Object> entry: indexDoc.entrySet()) {
      String val = entry.getValue().toString();
      String key = entry.getKey();
      if ( val.length() > 100) {
        val = val.substring(0,100);
      }
      addError(uri, "Key not reindexed: "+key+"="+val, true);
    }
  }
  
  /**
   * Convert an Entry contaning solr data to a string
   * @param data
   * @return The string data represented by the object
   */
  private final String toSolrString(final Object obj) {
    if ( obj instanceof List ) {
      @SuppressWarnings("unchecked")
      List<String> strList = (List<String>)obj;
      return StringUtils.join(strList.iterator(), " | ");
    }
    return obj.toString();
  }
  
  /**
   * Compare just the TEXT field of the index and archive docs
   * @param uri
   * @param indexDoc
   * @param doc
   */
  private void compareText(String uri, SolrDocument indexDoc, SolrDocument doc) {
    
    Object newTxtObj = doc.get("text");
    Object oldTxtObj = indexDoc.get("text");
    indexDoc.remove("text");
    
    String newTxt = getTextFromObject(uri, "new", newTxtObj);
    String oldTxt = getTextFromObject(uri, "old", oldTxtObj);
    
    // log additional errors if no new text and doc is flagged
    // such that it must have text (ocr or full text)
    if (newTxt == null) {
      String val = doc.get("has_full_text").toString();
      if ( val.equalsIgnoreCase("false")) {
        addError(uri, "field has_full_text is "+val+" but full text does not exist.");
      }
      
      val = doc.get("is_ocr").toString();
      if ( val.equalsIgnoreCase("false")) {
        addError(uri, "field is_ocr is "+val+" but full text does not exist.");
      }
    }
    
    if (newTxt == null && oldTxt != null) {
      addError(uri, "text field has disappeared from the new index. (old text size = "+oldTxt.length());
    } else if (newTxt != null && oldTxt == null) {
      addError(uri, "text field has appeared in the new index.");
    } else if (newTxt.equals(oldTxt) == false) {
    
      newTxt = getProcessedReindexedText(newTxt);
      oldTxt = getProcessedOrigText(oldTxt);  
      
      if (oldTxt.equals(newTxt) == false ) {        
        logMismatchedText(uri, oldTxt, newTxt);
      }
    }    
  }
  
  private void logMismatchedText(final String uri, final String oldTxt, final String newTxt) {
    int pos = StringUtils.indexOfDifference(newTxt, oldTxt);
    pos = Math.max(0, pos-4);
    String newSub = newTxt.substring(pos, Math.min(pos+51, newTxt.length()));
    String oldSub = oldTxt.substring(pos, Math.min(pos+51, oldTxt.length()));
    addError("txt", "==== "+uri+" mismatch at line 0 col "+pos+":");
    addError("txt", "(new "+newTxt.length()+")");
    addError("txt", newSub, true);
    addError("txt", "-- vs --");
    addError("txt", "(old "+oldTxt.length()+")");
    addError("txt", oldSub);
    addError("txt", "NEW: "+ getBytesString(newSub) );
    addError("txt", "OLD: "+ getBytesString(oldSub) );
    this.errorCount++;
  }


  private String getBytesString(String text) {
    try {
      byte[] bytes = text.getBytes( "UTF-8" );
      StringBuffer hexStr = new StringBuffer();
      for (int i=0; i<bytes.length; i++ ) {
        hexStr.append(Integer.toString(0xFF & bytes[i]) ).append(" ");
        if (hexStr.length() > 45 ) break;
      }
      return hexStr.toString();
    } catch ( Exception e) {
      addError("txt", "Invalid bytes in text: "+ e.getMessage());
      return "** ERROR **";
    }
  }


  private String getTextFromObject(String uri, String prefix, Object txtObj) {
    if ( txtObj == null) {
      return null;
    }
    
    if ( txtObj instanceof List ) {
      @SuppressWarnings("unchecked")
      List<String> dat = (List<String>)txtObj;
      addError(uri, prefix+" text is an array of size "+dat.size());
      StringBuffer sb = new StringBuffer();
      for (String s: dat) {
        if (sb.length() > 0) {
          sb.append(" | ");
        }
        sb.append( s);
      }
      return sb.toString();
    } else {
      return txtObj.toString().trim();
    }  
  }
 

  private String getProcessedOrigField(String origVal) {
    return removeExtraWhiteSpace(origVal);
  }
  private String getProcessedReindexedField(String origVal) {
    return removeExtraWhiteSpace(origVal);
  }
  
  private String getProcessedOrigText(String origTxt) {
    String val = origTxt.replaceAll("““", "“");
    val = val.replaceAll("””", "””");
    val = val.replaceAll("††", "†");
    val = val.replaceAll("\\—+", "—"); 
    return removeExtraWhiteSpace(val);
  }
  
  private String getProcessedReindexedText(String srcTxt ) {
    String val = srcTxt.replaceAll("““", "“");
    val = val.replaceAll("””", "””");
    val = val.replaceAll("††", "†");
    val = val.replaceAll("\\—+", "—"); 
    return removeExtraWhiteSpace(val);
  }
  
  private String removeExtraWhiteSpace(final String srcTxt) {
    String result = srcTxt.replaceAll("\t", " ");   // change tabs to spaces
    result = result.replaceAll("\\s+", " ");        // get rid of multiple spaces
    result = result.replaceAll(" \n", "\n");        // get rid of trailing spaces
    result = result.replaceAll("\n ", "\n");        // get rid of leading spaces
    result = result.replaceAll("\\n+", " ");        // get rid of lines
    return result;
  }


  /**
   * EXCEPTION case. Dont whine about fields we know are newly added
   * @param key
   * @return
   */
  private boolean isIgnoredNewField(String key) {
    if (key.equals("year_sort") || 
        key.equals("has_full_text") ||
        key.equals("freeculture") ||
        key.equals("is_ocr") ||
        key.equals("author_sort") ) {
      return true;
    }
    return false;
  }

  private void addError(String uri, String err) {
    addError(uri, err, false);
  }
  private void addError(String uri, String err, boolean tail) {
    if ( this.errors.containsKey(uri) == false) {
      this.errors.put(uri, new ArrayList<String>() );
    }
    
    if ( uri.equals("txt") || tail) {
      this.errors.get(uri).add(err);
    } else {
      this.errors.get(uri).add(0,err);
    }
    
    if ( uri.equals("txt") == false) {
      this.errorCount++;
    }
  }

  /**
   * Ensure that all required fields are present and contain data
   * @param uri URI of the document
   * @param doc Document XML data
   * @throws Exception
   */
  private void validateRequiredFields(SolrDocument doc)  {

    for ( String fieldName : REQUIRED_FIELDS) {

      // find the first element in the correct doc that
      // has a name attribute matching the  required field
      String uri = doc.get("uri").toString();
      Object docField = doc.get(fieldName);
      
      // make sure field is present
      if ( docField == null ) {
        
        addError(uri, "required field: "+fieldName+" missing in new index");
        
      } else {
        
        // if its an array, make sure it has children
        // and that the concatenated children content has length
        if ( docField instanceof List ) {
          @SuppressWarnings("unchecked")
          List<String> list = (List<String>)docField;
          String val = "";
          for ( String data: list) {
            val += data;
          }
          if (val.length() == 0) {
            addError(uri, "required ARR field: "+fieldName+" is all spaces in new index");
          }
        } else {
          if ( docField.toString().trim().length() == 0) {
            addError(uri, "required STR field: "+fieldName+" is all spaces in new index");
          }
        }
      }
    }
  }

  /**
   * Log data to file and System.out
   * @param msg
   */
  private void logInfo( final String msg) {
    log.info(msg);
    if ( this.sysOut != null ) {
      this.sysOut.println(msg);  
    } else {
      System.out.println(msg);
    }
    
  }
  
  /**
   * Generate a clean core name from an archive
   * @param archive
   * @return
   */
  private final String archiveToCoreName( final String archive) {
    return "archive_"+archive.replace(":", "_").replace(" ", "_").replace(",", "_");
  }

  /**
   * Get all data from the specified archive
   * @param core The SOLR core from which to retrieve documents
   * @param archive The SOLR archive to use
   * @param fields The list of fields to retrieve
   * @return A list of SolrDocuments
   */
  private final List<SolrDocument> getSolrDocuments(final String core, final String archive, final String fields) {
    
    // Start at beginning of list and return 500 hits at a time
    int page = 0;
    int size = 500;
    
    // When fieldlist includes test, and the archive is one that contains
    // large text fields, limit page size to 1
    if ( this.includesText && LARGE_TEXT_ARCHIVES.contains(archive)) {
      size = 1;
    }
    
    List<SolrDocument> results = new ArrayList<SolrDocument>();
    while (true) {
     
      try {
        
        List<SolrDocument> pageHits = getPageFromSolr(core, archive, page, size, fields);
        results.addAll(pageHits);
        if (pageHits.size()  < size ){
          
          break;
        } else {
          
          page += 1;
          System.out.print( "." );
        }
        
      } catch (IOException e) {
        System.err.println("Error retrieving data from solr:" + e.getMessage());
        e.printStackTrace();
      }
    }
    return results;
  }
  
  /**
   * Get one page of documents from solr
   * @param core The SOLR core to search
   * @param archive The SOLR archive to use
   * @param page Starting page number
   * @param pageSize Maximum hits to return
   * @param fields List of fields to return
   * @return List of SolrDocuments
   * @throws IOException
   */
  private final List<SolrDocument> getPageFromSolr(final String core, final String archive, 
      final int page, final int pageSize, final String fields) throws IOException {

    // build the request query string
    String query = this.config.solrBaseURL + "/" + core + "/select/?q=archive:"+archive;
    query = query + "&start="+(page*pageSize)+"&rows="+pageSize;
    query = query + "&fl="+fields;
    query = query + "&sort=uri+asc";
    query = query + "&wt=javabin";
    GetMethod get = new GetMethod( query );

    // Execute request
    try {
      int result;
      int solrRequestNumRetries = SOLR_REQUEST_NUM_RETRIES;
      do {
        result = this.httpClient.executeMethod(get);
        if (result != 200) {
          try {
            Thread.sleep(SOLR_REQUEST_RETRY_INTERVAL);
            log.info(">>>> postToSolr error in archive " + archive + ": " + result + " (retrying...)");
          } catch (InterruptedException e) {
            log.info(">>>> Thread Interrupted");
          }
        } else {
          if (solrRequestNumRetries != SOLR_REQUEST_NUM_RETRIES)
            log.info(">>>> postToSolr: " + archive + ":  (succeeded!)");
        }
        solrRequestNumRetries--;
      } while (result != 200 && solrRequestNumRetries > 0);

      if (result != 200) {
        throw new IOException("Non-OK response: " + result + "\n" );
      }

      JavaBinCodec jbc = new JavaBinCodec();
      @SuppressWarnings("rawtypes")
      SimpleOrderedMap map = (SimpleOrderedMap)jbc.unmarshal( get.getResponseBodyAsStream() );
      SolrDocumentList docList = (SolrDocumentList)map.get("response");
      return docList;

    } finally {
      // Release current connection to the connection pool once you are done
      get.releaseConnection();
    }
  }
}
