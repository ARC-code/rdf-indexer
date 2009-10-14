/** 
 *  Copyright 2007 Applied Research in Patacriticism and the University of Virginia
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 **/
package org.nines;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.rio.StatementHandler;
import org.openrdf.rio.StatementHandlerException;
import org.openrdf.sesame.sailimpl.memory.BNodeNode;
import org.openrdf.sesame.sailimpl.memory.LiteralNode;
import org.openrdf.sesame.sailimpl.memory.URINode;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import org.apache.commons.httpclient.ConnectTimeoutException;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.NoHttpResponseException;
import org.apache.log4j.Logger;

public class NinesStatementHandler implements StatementHandler {
  public final static Logger log = Logger.getLogger(NinesStatementHandler.class.getName());

  private HashMap<String, HashMap<String, ArrayList<String>>> documents;
  private String dateBNodeId;
  private HashMap<String, ArrayList<String>> doc;
  private Boolean title_sort_added = false;
  private Boolean author_sort_added = false;
  private String filename; 
  private RDFIndexerConfig config;
  private ErrorReport errorReport;
  private HttpClient httpClient;
  private String documentURI;
  private LinkCollector linkCollector;
  private static final int SOLR_REQUEST_NUM_RETRIES = 5; // how many times we should try to connect with solr before giving up
  private static final int SOLR_REQUEST_RETRY_INTERVAL = 30 * 1000; // milliseconds
  private Boolean ignore = false;

  public NinesStatementHandler( ErrorReport errorReport, LinkCollector linkCollector, RDFIndexerConfig config  ) {
	 this.errorReport = errorReport;
	 this.config = config;
         this.httpClient = new HttpClient();
	 doc = new HashMap<String, ArrayList<String>>();
	 documentURI = "";
	 documents = new HashMap<String, HashMap<String, ArrayList<String>>>();
	 this.linkCollector = linkCollector;
	System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
	System.setProperty ("org.apache.commons.logging.simplelog.showdatetime", "true");
      System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.commons.httpclient", "error");
  }
  
  public void handleStatement(Resource resource, URI uri, Value value) throws StatementHandlerException {
	  if (ignore)
		  return;

    String subject = resource.toString();
    String predicate = uri.getURI();
    String object = value.toString().trim();

    // if the object of the triple is blank, skip it, it is nothing worth indexing
    if (object == null || object.length() == 0)
      return;
    
    // start of a new document
    if ("http://www.w3.org/1999/02/22-rdf-syntax-ns#type".equals(predicate) && resource instanceof URINode) {
		if (documents.size() >= config.maxDocsPerFolder) {
			ignore = true;
	       log.info("*** Ignoring rest of file starting here: " + subject);
			return;
		}
      if (documents.get(subject) != null) {
        errorReport.addError(new IndexerError(filename, subject, "Duplicate URI"));
		log.info("*** Duplicate: " + subject);
      }
      doc = new HashMap<String, ArrayList<String>>();
      addField(doc, "uri", subject);
      documents.put(subject, doc);
	  title_sort_added = false;
	  author_sort_added = false;
      documentURI = subject;
      //if( documentURI.equals("") ) documentURI = subject;
      log.info("Parsing RDF for document: "+subject );
      errorReport.flush();
    }
    // Check for any unsupported nines:* attributes and issue error if any exist
    if (predicate.startsWith("http://www.nines.org/schema#")) {
      String attribute = predicate.substring("http://www.nines.org/schema#".length());
      if (! (attribute.equals("archive") || attribute.equals("freeculture") ||
          attribute.equals("source") ||
          attribute.equals("genre") || attribute.equals("thumbnail") || attribute.equals("text") ||
          attribute.equals("image"))) {

        errorReport.addError(
            new IndexerError(filename, documentURI, "NINES does not support this property: " + predicate));
      
        return;
      }
    }
    
    // parse RDF statements into fields, return when the statement has been handled    
    if( handleArchive(predicate, object) ) return;
    if( handleFreeCulture(predicate, object) ) return;
    if( handleTitle(predicate, object) ) return;
    if( handleAlternative(predicate, object) ) return;
    if( handleGenre(predicate, object) ) return;
    if( handleDate(subject, predicate, value) ) return;
    if( handleDateLabel(subject, predicate, object) ) return;
    if( handleSource(predicate, object) ) return;
    if( handleThumbnail(predicate, object) ) return;
    if( handleImage(predicate, object) ) return;
    if( handleURL(predicate, object) ) return;
    if( handleText(predicate, object) ) return;
    if( handleRole(predicate, object) ) return;
  }
  
  public boolean handleArchive( String predicate, String object ) {
	  if ("http://www.nines.org/schema#archive".equals(predicate)) {
	      addField(doc, "archive", object);
	      return true;
	  }
	  return false;
  }
    
  public boolean handleFreeCulture( String predicate, String object ) {
    if ("http://www.nines.org/schema#freeculture".equals(predicate)) {
      if ("false".equalsIgnoreCase(object)) {
        // only add a freeculture field if its false.  No field set implies "T"rue
        addField(doc, "freeculture", "F");  // "F"alse     
      }
      return true;
    }
    return false;
  }
  
  public boolean handleTitle( String predicate, String object ) {
    if ("http://purl.org/dc/elements/1.1/title".equals(predicate)) {
      addField(doc, "title", object);
	  if (!title_sort_added) {
		  addField(doc, "title_sort", object);
		  title_sort_added = true;
	  }
      return true;
    }
    return false;
  }
  
  public boolean handleAlternative( String predicate, String object ) {
    if ("http://purl.org/dc/terms/alternative".equals(predicate)) {
      addField(doc, "alternative", object);
      return true;
    }
    return false;
  }
  
  public boolean handleGenre( String predicate, String object ) {
    if ("http://www.nines.org/schema#genre".equals(predicate)) {
      // ignore deprecated genres for backward compatibility
      if (!"Primary".equals(object) && !"Secondary".equals(object)) {
        addField(doc, "genre", object);
      }
      return true;
    }
    return false;
  }
  
  public boolean handleDate( String subject, String predicate, Value value ) {
    if ("http://purl.org/dc/elements/1.1/date".equals(predicate)) {
      String object = value.toString().trim();
      if (value instanceof LiteralNode) {
        // For backwards compatibility of simple <dc:date>, but also useful for cases where label and value are the same
        if (object.matches("^[0-9]{4}.*")) {
          addField(doc, "year", object.substring(0, 4));
        }

        ArrayList<String> years = null;
        try {
          years = parseYears(object);
          
          if( years.size() == 0 ) {
              errorReport.addError( new IndexerError(filename, documentURI, "Invalid date format: " + object) );
              return false;
          }
          
          for (String year : years) {
            addField(doc, "year", year);
          }

          addField(doc, "date_label", object);
        } catch (NumberFormatException e) {
          errorReport.addError(
              new IndexerError(filename, documentURI, "Invalid date format: " + object));
        }
      } else {
        BNodeNode bnode = (BNodeNode) value;
        dateBNodeId = bnode.getID();
      }
      
      return true;
    }
    
    return false;
  }
  
  public boolean handleDateLabel( String subject, String predicate, String object ) {
    if (subject.equals(dateBNodeId)) {
      // if dateBNodeId matches, we assume we're under a <nines:date> and simply
      // look for <rdfs:label> and <rdf:value>

      if ("http://www.w3.org/2000/01/rdf-schema#label".equals(predicate)) {
        addField(doc, "date_label", object);
        return true;
      }

      if ("http://www.w3.org/1999/02/22-rdf-syntax-ns#value".equals(predicate)) {
        try {
          ArrayList<String> years = parseYears(object);
          for (String year : years) {
            addField(doc, "year", year);
          }
        } catch (NumberFormatException e) {
          errorReport.addError(
              new IndexerError(filename, documentURI, "Invalid date format: " + object));
        }
        return true;
      }
    }
    return false;
  }
  
  public boolean handleSource( String predicate, String object ) {
    if ("http://purl.org/dc/elements/1.1/source".equals(predicate)) {
      addField(doc, "source", object);
      return true;
    }
    return false;
  }
  
  public boolean handleThumbnail( String predicate, String object ) {
    if ("http://www.nines.org/schema#thumbnail".equals(predicate)) {
      addField(doc, "thumbnail", object);
      return true;
    }
    return false;
  }
  
  public boolean handleImage( String predicate, String object ) {
    if ("http://www.nines.org/schema#image".equals(predicate)) {
      addField(doc, "image", object);
      return true;
    }
    return false;
  }

  public boolean handleURL( String predicate, String object ) {
    if ("http://www.w3.org/2000/01/rdf-schema#seeAlso".equals(predicate)) {
      addField(doc, "url", object);
      return true;
    }
    return false;
  }
  
  public boolean handleText( String predicate, String object ) {
    if ("http://www.nines.org/schema#text".equals(predicate)) {
      try {
        String text = object;
        if (object.trim().startsWith("http://") && object.trim().indexOf(" ") == -1) {
          addFieldEntry(doc, "text_url", text);
		  if (text.endsWith(".pdf") || text.endsWith(".PDF")) {
			errorReport.addError(new IndexerError(filename, documentURI, "PDF file ignored for now: " + text));
			text = "";
		  }
		  else {
			 if( config.retrieveFullText ) text = fetchContent(object);
			  if (config.reindexFullText)
				  text = getFullText(doc.get("uri").get(0), httpClient );
		  }
        }
		if (text.length() > 0)
	        addFieldEntry(doc, "text", text);
//        addFieldEntry(doc, "content", text);
      } catch (IOException e) {
        String uriVal = documentURI;
        errorReport.addError(
            new IndexerError(filename, uriVal, e.getMessage()));
      }
      return true;
    }
    return false;
  }
  
  private String getFullText(String uri, HttpClient httpclient ) {
	  String fullText = "";
    String solrUrl = config.solrBaseURL + config.solrExistingIndex + "/select";

    GetMethod get = new GetMethod(solrUrl);
    NameValuePair queryParam = new NameValuePair("q", "uri:\""+uri + "\"");
    NameValuePair params[] = new NameValuePair[]{queryParam};
    get.setQueryString(params);

    int result;
    try {
      int solrRequestNumRetries = SOLR_REQUEST_NUM_RETRIES;
      do {
        result = httpclient.executeMethod(get);
        solrRequestNumRetries--;
        if(result != 200) {
          try {
            Thread.sleep(SOLR_REQUEST_RETRY_INTERVAL);
          } catch(InterruptedException e) {
            log.info(">>>> Thread Interrupted");
          }
        }
      } while(result != 200 && solrRequestNumRetries > 0);

      if (result != 200) {
        errorReport.addError(new IndexerError("","","cannot reach URL: " + solrUrl));
      }

      //String response = get.getResponseBodyAsString();
//		BufferedReader reader = new BufferedReader(get.getResponseBodyAsStream());
//		StringBuilder sb = new StringBuilder();
//		String line = null;
//		try {
//			while ((line = reader.readLine()) != null) {
//				sb.append(line + "\n");
//			}
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
//		fullText = sb.toString();

//		BufferedInputStream bis = new BufferedInputStream(get.getResponseBodyAsStream());
//	  byte[] b = new byte[4096];
//	  int len;
//	  while ((len = bis.read(b)) > 0) {
//        String str = new String(b, 0, len, get.getResponseCharSet());
//		  String start = "<arr name=\"text\"><str>";
//		  String stop = "</str></arr>";
//		  if (fullText.length() == 0) {
//			  int iStart = str.indexOf(start);
//			if (iStart >= 0) {
//			  fullText = str.substring(iStart+start.length());
//			  int iEnd = fullText.indexOf(stop);
//			  if (iEnd >= 0) {
//				  fullText = fullText.substring(0, iEnd);
//				  break;
//			  }
//			}
//		  } else {
//			  int iEnd = str.indexOf(stop);
//			  if (iEnd == -1)
//				  fullText += str;
//			  else {
//				  fullText += str.substring(0, iEnd);
//				  break;
//			  }
//		  }
//      }
	  //fullText = parseXML(response);
	  fullText = get.getResponseBodyAsString();
	  String start = "<arr name=\"text\"><str>";
	  String stop = "</str></arr>";
	 fullText = trimBracketed(fullText, start, stop);
//	  if (uri.equals("http://www.rossettiarchive.org/docs/2-1881.sigdadd.delms.rad"))
//	  {
//		  int iQuote = fullText.indexOf("‚Äú");
//		  int iDblQuote = fullText.indexOf("‚Äú‚Äú");
//		  int iQuote2 = fullText.indexOf("“");
//		  int iDblQuote2 = fullText.indexOf("““");
//		  errorReport.addError(new IndexerError("","","Quotes: " + iQuote + "," + iDblQuote + "," + iQuote2 + "," + iDblQuote2));
//		  String str = unescapeXML(fullText);
//		  if (str.length() != fullText.length())
//			  errorReport.addError(new IndexerError("","","Not same length"));
//	  }

    } catch (NoHttpResponseException e) {
      errorReport.addError(new IndexerError("","","The SOLR server didn't respond to the http request to: " + solrUrl));
    } catch (ConnectTimeoutException e) {
      errorReport.addError(new IndexerError("","","The SOLR server timed out on the http request to: " + solrUrl));
    } catch (IOException e) {
      errorReport.addError(new IndexerError("","","An IO Error occurred attempting to access: " + solrUrl));
	}
    finally {
      get.releaseConnection();
    }

    return unescapeXML(fullText);
}

  private String unescapeXML(String str) {
	 str = str.replaceAll("&lt;", "<");
	 str = str.replaceAll("&gt;", ">");
	 str = str.replaceAll("&amp;", "&");
	return str;
  }

//  private String parseXML(String str) {
//	  String start = "<arr name=\"text\"><str>";
//	  String stop = "</str></arr>";
//	 String fullText = trimBracketed(str, start, stop);
//	 fullText = replaceMatch(fullText, "&lt;", "<");
//	 fullText = replaceMatch(fullText, "&gt;", ">");
//	 fullText = replaceMatch(fullText, "&amp;", "&");
//	return fullText;
//    }



  public boolean handleRole( String predicate, String object ) {
    if (predicate.startsWith("http://www.loc.gov/loc.terms/relators/")) {
      String role = predicate.substring("http://www.loc.gov/loc.terms/relators/".length());
      addField(doc, "role_" + role, object);
	  if (!author_sort_added && ((role.equals("AUT")) || (role.equals("ART")))) {
		  addField(doc, "author_sort", object);
		  author_sort_added = true;
	  }
      return true;
    }
    return false;
  }

  
  public static ArrayList<String> parseYears(String value) {
    ArrayList<String> years = new ArrayList<String>();

    if ("unknown".equalsIgnoreCase(value.trim()) ||
        "uncertain".equalsIgnoreCase(value.trim())) {
      years.add("Uncertain");
    } else {

      // expand 184u to 1840-1849
      if (value.indexOf('u') != -1) {
        char[] yearChars = value.toCharArray();
        int numLength = value.length();
        int i, factor = 1, startPos = 0;

        if (numLength > 4) numLength = 4;

        // increase factor according to size of number
        for (i = 0; i < numLength; i++)
          factor *= 10;

        // start looking for 'u', decreasing factor as we go
        for (i = startPos; i < value.length(); i++) {
          if (yearChars[i] == 'u') {
            int padSize = value.length() - i;
            String formatStr = "%0" + padSize + "d";
            // iterate over each year
            for (int j = 0; j < factor; j++) {
              years.add(value.substring(0, i) + String.format(formatStr, j));
            }
            // once one 'u' char is found, we are done
            break;
          }
          factor = factor / 10;
        }
      } else {
        // 1862-12-25,1863-01-01 1875 1954-10
        StringTokenizer tokenizer = new StringTokenizer(value);
        while (tokenizer.hasMoreTokens()) {
          String range = tokenizer.nextToken();
          int commaPos = range.indexOf(',');
          String start, finish;
          if (commaPos == -1) {
            start = finish = range;
          } else {
            start = range.substring(0, commaPos);
            finish = range.substring(commaPos + 1);
          }
          if( start.length() >= 4 && finish.length() >= 4){
            years.addAll(enumerateYears(start.substring(0, 4), finish.substring(0, 4)));            
          }
        }

      }
    }

    return years;
  }

  public void addField(HashMap<String, ArrayList<String>> map, String name, String value) {
    // skip null fields
    if (value == null || name == null) return;

    // if the field is a url, check to see if it is reachable
    if ( config.collectLinks && value.trim().startsWith("http://") && value.trim().indexOf(" ") == -1 && !"uri".equals(name)) {
    	linkCollector.addLink(documentURI, filename, value);
    }

    addFieldEntry(map,name,value);
  }
  
  public static void addFieldEntry(HashMap<String, ArrayList<String>> map, String name, String value) {  
    // make sure we add to array for already existing fields
    if (map.containsKey(name)) {
      ArrayList<String> pastValues = map.get(name);
      pastValues.add(value);
      map.put(name, pastValues);
    } else {
      ArrayList<String> values = new ArrayList<String>();
      values.add(value);
      map.put(name, values);
    }
  }

  public HashMap<String, HashMap<String, ArrayList<String>>> getDocuments() {
    return documents;
  }

  private String fetchContent(String url) throws IOException {      
    GetMethod get = new GetMethod(url);
    int result;
    try {
      result = httpClient.executeMethod(get);
      if (result != 200) {
        throw new IOException(result+" code returned for URL: " + url );
      }

      BufferedInputStream bis = new BufferedInputStream(get.getResponseBodyAsStream());
      ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int len;

      while ((len = bis.read(b)) > 0) {
        contentStream.write(b, 0, len);
      }

      String fullText = contentStream.toString("UTF-8");
      String cleanedFullText = fullText;

	  cleanedFullText = cleanText(fullText);
      return cleanedFullText;
    } finally {
      get.releaseConnection();
    }
  }

	public String cleanText(String fullText) {
		// If the text contains markup, remove it.
		// We may be passed plain text, or we may be passed html, so any strategy we use needs to work for both.
		// We can assume that if it is plain text, it won't have stuff that looks like tags in it.
		if (fullText == null)
			return fullText;

		// remove everything between <head>...</head>
		fullText = removeTag(fullText, "head");

		// remove everything between <script>..</script>
		fullText = removeTag(fullText, "script");

		// remove all "&..;" encoding
		fullText = fullText.replaceAll("\\&[a-z]{1,5}\\;", " ");

		// remove everything between <...>
		fullText = removeBracketed(fullText, "<", ">");

		// Clean up the file a little bit
		fullText = fullText.replaceAll("\t", " ");
		fullText = fullText.replaceAll(" +", " ");
		fullText = replaceMatch(fullText, " \n", "\n");
		fullText = replaceMatch(fullText, "\n ", "\n");
		fullText = replaceMatch(fullText, "\n\n", "\n");


		//      if (fullText != null && fullText.indexOf("<") != -1) {
		//        return fullText.replaceAll("\\<.*?\\>", "");
		//      }
		  return fullText;
	}

  public static ArrayList<String> enumerateYears(String startYear, String endYear) {
    int y1 = Integer.parseInt(startYear);
    int y2 = Integer.parseInt(endYear);

    ArrayList<String> years = new ArrayList<String>();
    years.add(startYear);
    if (y2 <= y1) return years;

    for (int i = y1 + 1; i <= y2; i++) {
      years.add("" + i);
    }

    return years;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

	private String replaceMatch(String fullText, String match, String newText) {
		int start = fullText.indexOf(match);
		while (start != -1) {
			fullText = fullText.substring(0, start) + newText + fullText.substring(start + match.length());
			start = fullText.indexOf(match);
		}
		return fullText;
	}

	private String removeBracketed(String fullText, String left, String right) {
		int start = fullText.indexOf(left);
		while (start != -1) {
			int end = fullText.indexOf(right, start);
			if (end == -1) {
				start = -1;
			} else {
				fullText = fullText.substring(0, start) + "\n" + fullText.substring(end + right.length());
				start = fullText.indexOf(left);
			}
		}
		return fullText;
	}

	private String trimBracketed(String fullText, String left, String right) {
		int start = fullText.indexOf(left);
		if (start == -1)
			return "";
		start += left.length();
		int end = fullText.indexOf(right, start);
		if (end == -1)
			return "";
		return fullText.substring(start, end);
	}

	private String removeTag(String fullText, String tag) {
		return removeBracketed(fullText, "<" + tag, "</" + tag + ">");
	}
}
