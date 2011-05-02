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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.nines.RDFIndexerConfig.IndexMode;

public class RDFIndexer {

    private int numFiles = 0;
    private int numObjects = 0;
    private long largestTextSize = 0;
    private String guid = "";
    private RDFIndexerConfig config;
    private Queue<File> dataFileQueue;
    private ErrorReport errorReport;
    private LinkCollector linkCollector;
    private Logger log;
    private ExecutorService solrExecutorService;
    private StringBuilder solrXmlPayload;
    private int docCount = 0;
    private String targetArchive;
    private SolrClient solrClient; 

    /**
     * 
     * @param rdfSource
     * @param archiveName
     * @param config
     */
    public RDFIndexer(RDFIndexerConfig config) {

        this.config = config;

        // Use the SAX2-compliant Xerces parser:
        System.setProperty("org.xml.sax.driver", "org.apache.xerces.parsers.SAXParser");

        // get partial log filename
        String archiveName = config.archiveName;
        archiveName = archiveName.replaceAll("/", "_").replaceAll(":", "_").replaceAll(" ", "_");
        String logFileRelativePath = config.logRoot + "/";

        // config system based oncmdline flags
        if (this.config.compare) {
            initCompareMode(logFileRelativePath + archiveName);
        } else {
            initIndexMode(logFileRelativePath + archiveName);
        }
    }

    private void initIndexMode(final String logFileRoot) {

        // setup logger
        String indexLog = logFileRoot + "_progress.log";
        System.setProperty("index.log.file", indexLog);
        URL url = ClassLoader.getSystemResource("log4j-index.xml");
        DOMConfigurator.configure(url);
        this.log = Logger.getLogger(RDFIndexer.class.getName());

        // keep report file in the same folder as the log file.
        File reportFile = new File(logFileRoot + "_error.log");
        try {
            this.errorReport = new ErrorReport(reportFile);
        } catch (IOException e1) {
            this.log.error("Unable to open error report log for writing, aborting indexer.");
            return;
        }

        this.linkCollector = new LinkCollector(logFileRoot);
        this.solrClient = new SolrClient(this.config.solrBaseURL);

        try {
            this.solrClient.validateCore( archiveToCore(config.archiveName) );
        } catch (IOException e) {
            this.errorReport.addError(new IndexerError("Creating core", "", e.getMessage()));
        }

        // if a purge was requested, do it first
        if (config.deleteAll) {
            purgeArchive( archiveToCore(config.archiveName));
        }

        // log text mode
        if ( this.config.indexMode.equals( IndexMode.FULL) ) {
            this.log.info("Online: Indexing Full Text");
        } else  if ( this.config.indexMode.equals( IndexMode.REINDEX) ) {
            this.log.info("Offline: Not Indexing Full Text");
        } else {
            this.log.info("*** TEST MODE: Not committing documents to SOLR");
        }

        // do the indexing
        createGUID(this.config.rdfSource);
        Date start = new Date();
        log.info("Started indexing at " + start);
        System.out.println("Indexing "+config.rdfSource);
        indexDirectory(config.rdfSource);
        System.out.println("Indexing DONE");

        // report indexing stats
        Date end = new Date();
        double durationSec = (end.getTime() - start.getTime()) / 1000.0;
        if (durationSec >= 60) {
            this.log.info(String.format(
                "Indexed " + numFiles + " files (" + numObjects + " objects) in %3.2f minutes.", (durationSec / 60.0)));
        } else {
            this.log.info(String.format(
                "Indexed " + numFiles + " files (" + numObjects + " objects) in %3.2f seconds.", durationSec));
        }
        this.log.info("Largest text field size: " + this.largestTextSize);
        this.errorReport.close();
        this.linkCollector.close();
    }

    private void initCompareMode(final String logFileRoot) {

        String compareLog = logFileRoot + "_compare.log";
        String skippedLog = logFileRoot + "_skipped.log";
        String compareTxtLog = logFileRoot + "_compare_text.log";

        System.setProperty("compare.log.file", compareLog);
        System.setProperty("compare.text.log.file", compareTxtLog);
        System.setProperty("skipped.log.file", skippedLog);
        URL url = ClassLoader.getSystemResource("log4j-compare.xml");
        DOMConfigurator.configure(url);

        RDFCompare rdfCompare = new RDFCompare(config);
        rdfCompare.compareArchive();
    }

    private void purgeArchive(final String coreName) {
        log.info("DELETING ALL INDEX DATA FROM CORE: " + coreName);
        try {
            this.solrClient.post("<delete><query>*:*</query></delete>", coreName);
            this.solrClient.post("<commit/>", coreName);
        } catch (IOException e) {
            errorReport.addError(new IndexerError("", "", "Unable to POST DELETE message to SOLR. "
                + e.getLocalizedMessage()));
        }
    }

    private void createGUID(File rdfSource) {
        String path = rdfSource.getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);

        try {
            guid = file.substring(0, file.indexOf('.'));
        } catch (StringIndexOutOfBoundsException e) {
            /*
             * In cases where the indexer is run manually against a directory that doesn't specify the GUID, create one
             * automatically.
             */
            guid = java.util.UUID.randomUUID().toString();
        }
    }

    private void recursivelyQueueFiles(File dir) {
        if (dir.isDirectory()) {
            log.info("loading directory: " + dir.getPath());

            File fileList[] = dir.listFiles();
            for (File entry : fileList) {
                if (entry.isDirectory() && !entry.getName().endsWith(".svn")) {
                    recursivelyQueueFiles(entry);
                }
                if (entry.getName().endsWith(".rdf") || entry.getName().endsWith(".xml")) {
                    this.dataFileQueue.add(entry);
                }
            }
        } else // a file was passed in, not a folder
        {
            this.log.info("loading file: " + dir.getPath());
            this.dataFileQueue.add(dir);
        }
    }

    private void indexDirectory(File rdfDir) {
        this.dataFileQueue = new LinkedList<File>();
        recursivelyQueueFiles(rdfDir);
        this.numFiles = this.dataFileQueue.size();
        log.info("=> Indexing " + rdfDir + " total files: " + this.numFiles);
        this.solrExecutorService = Executors.newFixedThreadPool( 2 );

        this.targetArchive = "";
        this.docCount = 0;
        this.solrXmlPayload = new StringBuilder();
        while (this.dataFileQueue.size() > 0) {
            File rdfFile = this.dataFileQueue.remove();
            indexFile(rdfFile);
        }
        
        if ( this.solrXmlPayload.length() > 0) {
            this.solrExecutorService.execute( new SolrWorker(this.solrXmlPayload, this.targetArchive, this.docCount) );
        }
        
        if ( this.config.isTestMode() == false  ) {
            try {
                this.solrClient.post("<commit/>", this.targetArchive);
            } catch (IOException e) {
                this.log.error("Commit to SOLR FAILED: "+e.getMessage());
            }
        }
        
        // signal shutdown and wait unti it is comlete
        this.solrExecutorService.shutdown();
        try {
            this.solrExecutorService.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {}
    }
    
    private boolean postThresholdMet() {
        return (this.solrXmlPayload.length() >= this.config.maxUploadSize);
    }

    /**
     * Generate a core name given the archive. The core name is of the format: archive_[name]
     * 
     * @param archive
     * @return
     */
    private String archiveToCore(String archive) {
        String core = archive.replaceAll(":", "_");
        core = core.replaceAll(" ", "_");
        core = core.replaceAll(",", "_");
        return "archive_" + core;
    }

    private void indexFile(File file) {
   
        HashMap<String, HashMap<String, ArrayList<String>>> objects;

        // Parse a file into a hashmap.
        // Key is object URI, Value is a set of key-value pairs
        // that describe the object
        try {
            objects = RdfDocumentParser.parse(file, this.errorReport, this.linkCollector, this.config);
        } catch (IOException e) {
            this.errorReport.addError(new IndexerError(file.getName(), "", e.getMessage()));
            return;
        }

        // Log an error for no objects abd bail if size is zero
        if (objects == null || objects.size() == 0) {
            errorReport.addError(new IndexerError(file.getName(), "", "No objects in this file."));
            errorReport.flush();
            return;
        }

        // save the largest text field size
        this.largestTextSize = Math.max(this.largestTextSize, RdfDocumentParser.getLargestTextSize());

      
        XMLOutputter outputter = new XMLOutputter(Format.getRawFormat());
        for (Map.Entry<String, HashMap<String, ArrayList<String>>> entry : objects.entrySet()) {

            this.targetArchive = "";
            String uri = entry.getKey();
            HashMap<String, ArrayList<String>> object = entry.getValue();

            // Validate archive and push objects intop new archive map
            ArrayList<String> objectArray = object.get("archive");
            if (objectArray != null) {
                String objArchive = objectArray.get(0);
                this.targetArchive = archiveToCore(objArchive);
                if (!objArchive.equals(this.config.archiveName)) {
                    this.errorReport.addError(new IndexerError(file.getName(), uri, "The wrong archive was found. "
                        + objArchive + " should be " + this.config.archiveName));
                }
            } else {
                this.errorReport.addError(new IndexerError(file.getName(), uri,
                    "Unable to determine archive for this object."));
            }

            // validate all other parts of object and generate error report
            try {
                ArrayList<ErrorMessage> messages = ValidationUtility.validateObject(object);
                for (ErrorMessage message : messages) {
                    IndexerError e = new IndexerError(file.getName(), uri, message.getErrorMessage());
                    errorReport.addError(e);
                }
            } catch (Exception valEx) {
                System.err.println("ERROR Validating file:" + file.getName() + " URI: " + uri);
                valEx.printStackTrace();
                IndexerError e = new IndexerError(file.getName(), uri, valEx.getMessage());
                errorReport.addError(e);
            }

            // turn this object into an XML solr docm then xml string. Add this to the curr payload
            Element document = convertObjectToSolrDOM(uri, object);
            String xmlString = outputter.outputString(document);
            this.solrXmlPayload.append( xmlString );
            this.docCount++;
            
            // once threshold met, post the data to solr
            if ( postThresholdMet() ) {
                if ( this.config.isTestMode() == false ) {
                    this.solrExecutorService.execute( new SolrWorker(this.solrXmlPayload, this.targetArchive, this.docCount) );
                }
                this.solrXmlPayload = new StringBuilder();
                this.docCount = 0;
            }
        }

        this.numObjects += objects.size();
        this.errorReport.flush();
    }

    private Element convertObjectToSolrDOM(String documentName, HashMap<String, ArrayList<String>> fields) {

        Element doc = new Element("doc");
        for (Map.Entry<String, ArrayList<String>> entry : fields.entrySet()) {

            String field = entry.getKey();
            ArrayList<String> valList = entry.getValue();

            for (String value : valList) {
                Element f = new Element("field");
                f.setAttribute("name", field);
                ValidationUtility.populateTextField(f, value);
                doc.addContent(f);
            }
        }

        // tag the document with the batch id
        Element f = new Element("field");
        f.setAttribute("name", "batch");
        f.setText(guid);
        doc.addContent(f);

        return doc;
    }

    /**
     * Worker thread to post data to solr
     * 
     * @author loufoster
     * 
     */
    private class SolrWorker implements Runnable {

        private final String payload;
        private final String tgtArchive;
        
        public SolrWorker( final StringBuilder data, final String tgtArchive, int docCnt ) {
            this.tgtArchive = tgtArchive;
            this.payload = "<add>" + data.toString() + "</add>";
            
            log.info("  posting: payload size " + this.payload.length() 
                + " with " + docCnt + " documents to SOLR");
        }
        public void run() {
            try {
                solrClient.post(this.payload, this.tgtArchive);
            } catch (IOException e) {
                Logger.getLogger(RDFIndexer.class.getName()).error("Post to SOLR FAILED: "+e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * MAIN Main application entry point
     * 
     * @param args
     */
    public static void main(String[] args) {

        // Option constants
        final String logDir = "logDir";         // both: logging directory
        final String deleteFlag = "delete";     // delete an archive from solr
        final String fullTextFlag = "fulltext"; // This goes to the website to get the full text.
        final String reindexFlag = "reindex";   // Get text from the current index instead external
        final String compareFlag = "compare";   // Compare mode
        final String ignoreFlag = "ignore";     // both: A list of fields to ignore
        final String includeFlag = "include";   // both: A list of fields to include
        final String source = "source";         // index: REQUIRED path to archive
        final String test = "test";             // index: Test mode, no post to solr
        final String archive = "archive";       // both: REQUIRED name of archive
        final String pageSize = "pageSize";     // compare: max results per solr page
        final String maxSize = "maxSize";       // indexing: the max size of data to send to solr

        // define the list of command line options
        Options options = new Options();
        Option srcOpt = new Option(source, true, "Path to the target RDF archive directory");
        options.addOption(srcOpt);
        Option nameOpt = new Option(archive, true, "The name of of the archive");
        nameOpt.setRequired(true);
        options.addOption(nameOpt);

        // MODES option group. Only one of fullTxt, reindex, test or compare
        OptionGroup modeOpts = new OptionGroup();
        modeOpts.addOption(new Option(fullTextFlag, false, "Retrieve full text from web"));
        modeOpts.addOption(new Option(reindexFlag, false, "Retrieve full text from current index"));
        modeOpts.addOption(new Option(test, false, "Test archive indexing - do not post to solr"));
        modeOpts.addOption(new Option(compareFlag, false, "Compare archive with index"));
        options.addOptionGroup(modeOpts);

        // include/exclude field group
        OptionGroup fieldOpts = new OptionGroup();
        fieldOpts.addOption(new Option(ignoreFlag, true,
            "Comma separated list of fields to ignore in compare. Default is none."));
        fieldOpts.addOption(new Option(includeFlag, true,
            "Comma separated list of fields to include in compare. Default is all."));
        options.addOptionGroup(fieldOpts);

        options.addOption(deleteFlag, false, "Delete ALL itemss from an existing archive");
        options.addOption(logDir, true, "Set the root directory for all indexer logs");
        options.addOption(pageSize, true,
            "Set max documents returned per solr page. Default = 500 for most, 1 for special cases");

        // create parser and handle the options
        RDFIndexerConfig config = new RDFIndexerConfig();
        CommandLineParser parser = new GnuParser();
        try {
            CommandLine line = parser.parse(options, args);

            // required params:
            config.archiveName = line.getOptionValue(archive);

            // determine mode
            if (line.hasOption(fullTextFlag)) {
                config.indexMode = IndexMode.FULL;
            } else if (line.hasOption(reindexFlag)) {
                config.indexMode = IndexMode.REINDEX;
            } else if (line.hasOption(test) ) {
                config.indexMode = IndexMode.TEST;
            }

            // source dir
            if (line.hasOption(source)) {
                config.rdfSource = new File(line.getOptionValue(source));
            }
            if (line.hasOption(maxSize)) {
                config.maxUploadSize = Long.parseLong(line.getOptionValue(source));
            }
            if (line.hasOption(pageSize)) {
                config.pageSize = Integer.parseInt(line.getOptionValue(pageSize));
            }
            if (line.hasOption(logDir)) {
                config.logRoot = line.getOptionValue(logDir);
            }
            
            config.deleteAll = line.hasOption(deleteFlag);

            // compare stuff
            config.compare = line.hasOption(compareFlag);
            if (line.hasOption(includeFlag)) {
                config.includeFields = line.getOptionValue(includeFlag);
            }
            if (line.hasOption(ignoreFlag)) {
                config.ignoreFields = line.getOptionValue(ignoreFlag);
            }

            // if we are indexing, make sure source is present
            if (config.compare == false && config.rdfSource == null) {
                throw new ParseException("Missing required -source parameter");
            }

        } catch (ParseException exp) {

            System.out.println("Error parsing options: " + exp.getMessage());

            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("rdf-idexer", options);
            System.exit(-1);
        }

        // Launch the indexer with the parsed config
        try {
            new RDFIndexer(config);
        } catch (Exception e) {
            Logger.getLogger(RDFIndexer.class.getName()).error("Unhandled exception: " + e.toString());
        }
        System.exit(0);
    }
}
