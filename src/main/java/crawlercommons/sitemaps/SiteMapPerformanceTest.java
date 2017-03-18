/**
 * Copyright 2016 Crawler-Commons
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package crawlercommons.sitemaps;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.httpclient.ChunkedInputStream;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.StatusLine;
import org.apache.commons.httpclient.util.EncodingUtil;
import org.apache.commons.io.IOUtils;
import org.archive.format.arc.ARCConstants;
import org.archive.io.ArchiveRecord;
import org.archive.io.ArchiveRecordHeader;
import org.archive.io.warc.WARCReader;
import org.archive.io.warc.WARCReaderFactory;
import org.archive.util.LaxHttpParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import crawlercommons.sitemaps.AbstractSiteMap.SitemapType;   

public class SiteMapPerformanceTest {

    private static Logger LOG = LoggerFactory.getLogger(SiteMapPerformanceTest.class);
    
    protected Map<String,WarcRecord> records = new LinkedHashMap<>();
    protected List<String> warcFiles = new ArrayList<>();
    protected Counter counter = new Counter();

    protected boolean indexed = new Boolean(System.getProperty("warc.index"));


    protected class WarcRecord {
        long offset;
        long contentOffset;
        int status;
        int warcFileId;
        boolean isProcessed = false;
        boolean isChunkedTransferEncoding = false;
        String contentType;

        private int parseHttpHeader(ArchiveRecord record) throws IOException {
            byte[] statusBytes = LaxHttpParser.readRawLine(record);
            String statusLineStr = EncodingUtil.getString(statusBytes, 0, statusBytes.length, ARCConstants.DEFAULT_ENCODING);
            if ((statusLineStr == null) || !StatusLine.startsWithHTTP(statusLineStr)) {
                LOG.error("Invalid HTTP status line: {}", statusLineStr);
            }
            int status = 0;
            try {
                StatusLine statusLine = new StatusLine(statusLineStr.trim());
                status = statusLine.getStatusCode();
            } catch (HttpException e) {
                LOG.error("Invalid HTTP status line '{}': {}", statusLineStr, e.getMessage());
            }
            Header[] headers = LaxHttpParser.parseHeaders(record, ARCConstants.DEFAULT_ENCODING);
            for (Header h : headers) {
                // save MIME type sent by server
                if (h.getName().equalsIgnoreCase("Content-Type")) {
                    contentType = h.getValue();
                } else if (h.getName().equalsIgnoreCase("Transfer-Encoding")) {
                    if (h.getValue().trim().equalsIgnoreCase("chunked")) {
                        isChunkedTransferEncoding = true;
                    }
                }
            }
            return status;
        }

        public WarcRecord(ArchiveRecord record) throws IOException {
            ArchiveRecordHeader header = record.getHeader();
            offset = header.getOffset();
            status = parseHttpHeader(record);
            contentOffset = record.getPosition()+1;
        }
        
        public byte[] getContent() throws IOException {
            // must re-open WARC file, no backward seek supported, no forward seek in gzipped WARC files
            WARCReader reader = WARCReaderFactory.get(warcFiles.get(warcFileId));
            ArchiveRecord record = reader.get(offset);
            record.skip(contentOffset); // skip HTTP header
            byte[] content = getContent(record);
            reader.close();
            return content;
        }

        public byte[] getContent(ArchiveRecord record) throws IOException {
            byte[] content = IOUtils.toByteArray(record, record.available());
            if (isChunkedTransferEncoding) {
                try {
                    ChunkedInputStream chunked = new ChunkedInputStream(new ByteArrayInputStream(content));
                    return IOUtils.toByteArray(chunked);
                } catch (IOException e) {
                    LOG.warn("Failed to read chunked transfer encoding: {}", e);
                }
            }
            return content;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("warc-file-id=").append(warcFileId);
            sb.append(", offset=").append(offset);
            sb.append(", content-offset=").append(contentOffset);
            sb.append(", status=").append(status);
            return sb.toString();
        }
    }

    protected interface ArchiveRecordProcessor {
        public void process(ArchiveRecord record) throws IOException;
    }

    protected class ArchiveRecordIndexer implements ArchiveRecordProcessor {
        private int warcId;
        public ArchiveRecordIndexer(int warcId) {
            this.warcId = warcId;
        }
        public void setWarcId(int warcId) {
            this.warcId = warcId;
        }
        public void process(ArchiveRecord record) throws IOException {
            String url = record.getHeader().getUrl();
            WarcRecord warcRecord = new WarcRecord(record);
            warcRecord.warcFileId = this.warcId;
            records.put(url, warcRecord);
        }
    }
    
    protected class ArchiveRecordSitemapParser implements ArchiveRecordProcessor {
        private SiteMapParser parser;
        public ArchiveRecordSitemapParser(SiteMapParser parser) {
            this.parser = parser;
        }
        public void process(ArchiveRecord record) throws IOException {
            String url = record.getHeader().getUrl();
            WarcRecord warcRecord = new WarcRecord(record);
            byte[] content = warcRecord.getContent(record);
            processRecord(parser, url, warcRecord, content, false);
        }
    }

    public void readWarcFile(String warcPath, ArchiveRecordProcessor proc) throws MalformedURLException, IOException {
        WARCReader reader = WARCReaderFactory.get(warcPath);
        int records = 0;
        for (ArchiveRecord record : reader) {
            ArchiveRecordHeader header = record.getHeader();
            if (!"application/http;msgtype=response".equals(header.getMimetype()))
                continue;
            records++;
            proc.process(record);                
            if ((records % 1000) == 0) {
                LOG.info("Read {} WARC response records", records);
            }
        }
        LOG.info("Read {} WARC response records from file {}", records, warcPath);
    }

    public WarcRecord getRecord(String url) {
        return records.get(url);
    }

    protected class Counter {
        int processed = 0;
        int processedSubSitemaps = 0;
        int failedFetches = 0;
        int failedParses = 0;
        int nUrls = 0;
        Map<String,Integer> byType = new HashMap<>();
        public Counter() {
            for (SitemapType type : SitemapType.values()) {
                byType.put(type.name(), 0);
            }
        }
    }
    
    protected void processRecord(SiteMapParser parser, String urlString, WarcRecord record, byte[] content, boolean isSubsitemap) {
        LOG.debug("Processing sitemap {}", urlString);
        if (record == null) {
            // try to achieve indexed record
            record = getRecord(urlString);
            if (record == null) {
                LOG.debug("No WARC record found for {}", urlString);
                return;
            }
        }
        if (record.isProcessed) {
            LOG.debug("WARC record already processed, skipping {}", urlString);
            return;
        }
        record.isProcessed = true;
        if (record.status != 200) {
            // TODO: follow redirects if indexed
            LOG.warn("Failed to fetch {} (HTTP status = {})", urlString, record.status);
            counter.failedFetches++;
            return;
        }
        if (isSubsitemap) {
            counter.processedSubSitemaps++;
        } else {
            counter.processed++;
        }
        if (content == null) {
            try {
                content = record.getContent();
            } catch (IOException e) {
                LOG.error("Failed to get record for {}: {}", urlString, record);
                return;
            }
        }
        AbstractSiteMap sitemap;
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL {}: {}", urlString, e);
            counter.failedFetches++;
            return;
        }
        long start = System.currentTimeMillis();
        try {
            LOG.debug("Parsing sitemap {}", url);
            sitemap = parser.parseSiteMap(content, url);
        } catch (UnknownFormatException e) {
            LOG.error("Failed to parse sitemap {}: {}", urlString, e);
            counter.failedParses++;
            return;
        } catch (IOException e) {
            LOG.error("Error processing sitemap {}: {}", urlString, e);
            counter.failedParses++;
            return;
        } finally {
            long elapsed = (System.currentTimeMillis() - start);
            if (elapsed > 300) {
                LOG.warn("Process sitemap {} took {}ms", urlString, elapsed);
            }
        }
        if (sitemap.isIndex()) {
            if (isSubsitemap) {
                LOG.warn("Recursive sitemap index skipped: {}", urlString);
            } else {
                Collection<AbstractSiteMap> links = ((SiteMapIndex) sitemap).getSitemaps();
                for (AbstractSiteMap asm : links) {
                    processRecord(parser, asm.getUrl().toString(), null, null, true);
                }
            }
        } else {
            counter.nUrls += ((SiteMap) sitemap).getSiteMapUrls().size();
        }
        if ((counter.processed % 50) == 0) {
            LOG.info("Processed {} sitemaps, {} URLs extracted.", counter.processed, counter.nUrls);
        }
        String type = sitemap.getType().toString();
        counter.byType.put(type, counter.byType.get(type) + 1);
    }

    public void run(SiteMapParser parser, String[] warcPaths) throws MalformedURLException, IOException {
        if (indexed) {
            ArchiveRecordProcessor proc = new ArchiveRecordIndexer(0);
            for (String warcPath : warcPaths) {
                int warcId = warcFiles.size();
                ((ArchiveRecordIndexer) proc).setWarcId(warcId);
                warcFiles.add(warcPath);
                readWarcFile(warcPath, proc);
            }
        }

        long start = System.currentTimeMillis();
        
        if (indexed) {
            for (Entry<String, WarcRecord> e: records.entrySet()) {
                processRecord(parser, e.getKey(), e.getValue(), null, false);
            }
        } else {
            ArchiveRecordProcessor proc = new ArchiveRecordSitemapParser(parser);
            for (String warcPath : warcPaths) {
                readWarcFile(warcPath, proc);
            }
        }

        LOG.info("Finished processing, elapsed: {} ms", (System.currentTimeMillis() - start));
        LOG.info("{}\tprocessed sitemaps", counter.processed);
        LOG.info("{}\tprocessed subsitemaps from sitemap indexes", counter.processedSubSitemaps);
        LOG.info("{}\tfailed to fetch sitemap", counter.failedFetches);
        LOG.info("{}\tfailed to parse sitemap", counter.failedParses);
        LOG.info("{}\tURLs extracted from sitemaps", counter.nUrls);
        for (String type : counter.byType.keySet()) {
            LOG.info("{}\t{} sitemaps", counter.byType.get(type), type);
        }
    }

    public static void main(String[] args) throws MalformedURLException, IOException {
        
        if (args.length < 1) {
            LOG.error("Usage:  SiteMapPerformanceTest <WARC-file>...");
            LOG.error("Java properties:");
            LOG.error("  sitemap.useSax  if true use SAX parser to process sitemaps");
            LOG.error("  sitemap.strict  strict URL checking (no cross-submits)");
            LOG.error("  sitemap.partial accept URLs from partially parsed or invalid documents");
            LOG.error("  warc.index      index WARC files and parse sitemap indexes recursively");
            System.exit(1);
        }

        SiteMapParser parser;
        boolean useSaxParser = new Boolean(System.getProperty("sitemap.useSax"));
        boolean sitemapStrict = new Boolean(System.getProperty("sitemap.strict"));
        if (useSaxParser) {
            boolean sitemapPartial = new Boolean(System.getProperty("sitemap.partial"));
            parser = new SiteMapParserSAX(sitemapStrict, sitemapPartial);
        } else {
            parser = new SiteMapParser(sitemapStrict);
        }
        LOG.info("Using {}", parser.getClass());

        SiteMapPerformanceTest test = new SiteMapPerformanceTest();

        test.run(parser, args);
    }

}
