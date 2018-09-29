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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import org.archive.io.ArchiveRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import crawlercommons.sitemaps.AbstractSiteMap.SitemapType;
import crawlercommons.sitemaps.extension.Extension;

public class SiteMapPerformanceTest extends WarcTestProcessor {

    private static Logger LOG = LoggerFactory.getLogger(SiteMapPerformanceTest.class);
    
    protected Counter counter = new Counter();

    protected boolean indexed = new Boolean(System.getProperty("warc.index"));
    protected boolean enableSitemapExtensions = new Boolean(System.getProperty("sitemap.extensions"));
    protected String urlToBeParsed = System.getProperty("warc.parse.url");

    protected class ArchiveRecordSitemapParser implements ArchiveRecordProcessor {
        private SiteMapParser parser;
        private Set<String> acceptedUrls = new HashSet<>();
        public ArchiveRecordSitemapParser(SiteMapParser parser) {
            this.parser = parser;
        }
        public void filterAllowUrl(String url) {
            acceptedUrls.add(url);
        }
        public void process(ArchiveRecord record) throws IOException {
            String url = getUrl(record);
            if (!acceptedUrls.isEmpty() && !acceptedUrls.contains(url)) {
                return;
            }
            WarcRecord warcRecord = new WarcRecord(record);
            byte[] content = warcRecord.getContent(record);
            processRecord(parser, url, warcRecord, content, false);
        }
    }

    protected class Counter extends WarcTestProcessor.Counter {
        int processedSubSitemaps = 0;
        int failedParse = 0;
        int nUrls = 0;
        Map<String,Integer> byType = new HashMap<>();
        int nUrlsWithExtension = 0;
        Map<String,Integer> urlsWithExtension = new HashMap<>();

        public Counter() {
            for (SitemapType type : SitemapType.values()) {
                byType.put(type.name(), 0);
            }
        }

        public void log(Logger log) {
            super.log(log);
            log.info("{}\tfailed to parse sitemap", f(failedParse));
            log.info("{}\tprocessed subsitemaps from sitemap indexes", f(counter.processedSubSitemaps));
            log.info("{}\tURLs extracted from sitemaps", f(nUrls));
            for (String type : byType.keySet()) {
                log.info("{}\t{} sitemaps", f(byType.get(type)), type);
            }
            log.info("{}\tURLs with sitemap extension attribute(s):", f(nUrlsWithExtension));
            for (String ext : urlsWithExtension.keySet()) {
                log.info("{}\t{}", f(urlsWithExtension.get(ext)), ext);
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
            counter.failedFetch++;
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
            counter.failedFetch++;
            return;
        }
        long start = System.currentTimeMillis();
        try {
            LOG.debug("Parsing sitemap {}", url);
            sitemap = parser.parseSiteMap(content, url);
        } catch (UnknownFormatException e) {
            LOG.error("Failed to parse sitemap {}: {}", urlString, e);
            counter.failedParse++;
            return;
        } catch (IOException e) {
            LOG.error("Error processing sitemap {}: {}", urlString, e);
            counter.failedParse++;
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
            int size = ((SiteMap) sitemap).getSiteMapUrls().size();
            LOG.info("Extracted {} URLs from {} ({})", size, urlString, sitemap.getType());
            counter.nUrls += size;
            Set<Extension> usedExtensions = new TreeSet<>();
            if (enableSitemapExtensions) {
                for (SiteMapURL su : ((SiteMap) sitemap).getSiteMapUrls()) {
                    if (su.getAttributes() != null) {
                        counter.nUrlsWithExtension++;
                        for (Extension ext : su.getAttributes().keySet()) {
                            usedExtensions.add(ext);
                            Integer cnt = counter.urlsWithExtension.get(ext.toString());
                            counter.urlsWithExtension.put(ext.toString(), cnt == null ? 1 : 1 + cnt);
                        }
                    }
                }
                for (Extension ext : usedExtensions) {
                    String extType = "  XML " + ext.toString() + " sitemaps";
                    Integer cnt = counter.byType.get(extType);
                    counter.byType.put(extType, cnt == null ? 1 : 1 + cnt);
                }
            }
        }
        if ((counter.processed % 50) == 0) {
            LOG.info("Processed {} sitemaps, {} URLs extracted.", counter.processed, counter.nUrls);
        }
        String type = sitemap.getType().toString();
        counter.byType.put(type, counter.byType.get(type) + 1);
        counter.success++;
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

        if (urlToBeParsed != null) {
            LOG.info("Parsing sitemap for URL <{}>", this.urlToBeParsed);
        }
        
        long start = System.currentTimeMillis();

        if (indexed) {
            for (Entry<String, WarcRecord> e : records.entrySet()) {
                if (urlToBeParsed == null || urlToBeParsed.equals(e.getKey())) {
                    processRecord(parser, e.getKey(), e.getValue(), null, false);
                } else {
                    LOG.debug("Skipping URL <{}>", e.getKey());
                }
            }
        } else {
            ArchiveRecordSitemapParser proc = new ArchiveRecordSitemapParser(parser);
            if (urlToBeParsed != null) {
                proc.filterAllowUrl(urlToBeParsed);
            }
            for (String warcPath : warcPaths) {
                readWarcFile(warcPath, proc);
            }
        }

        LOG.info("Finished processing, elapsed: {} ms", (System.currentTimeMillis() - start));
        counter.log(LOG);
    }

    public static void main(String[] args) throws MalformedURLException, IOException {
        
        if (args.length < 1) {
            LOG.error("Usage:  SiteMapPerformanceTest <WARC-file>...");
            LOG.error("Java properties:");
            LOG.error("  sitemap.strict  (boolean) strict URL checking (no cross-submits)");
            LOG.error("  sitemap.partial (boolean) accept URLs from partially parsed or invalid documents");
            LOG.error("  sitemap.strictNamespace (boolean) enable strict namespace checking");
            LOG.error("  sitemap.lazyNamespace (boolean) enable lazy namespace checking");
            LOG.error("  sitemap.extension (boolean) enable support for sitemap extensions");
            //LOG.error("  sitemap.disableMimeDetection (boolean) disable detection of MIME types");
            LOG.error("  warc.index      (boolean) index WARC files and parse sitemap indexes recursively");
            LOG.error("  warc.parse.url  (String/URL) parse sitemap indexed by URL");
            LOG.error("                            (recursively if it's a sitemap index and warc.index is true)");
            System.exit(1);
        }

        SiteMapPerformanceTest test = new SiteMapPerformanceTest();

        boolean sitemapStrict = new Boolean(System.getProperty("sitemap.strict"));
        boolean sitemapPartial = new Boolean(System.getProperty("sitemap.partial"));
        SiteMapParser parser = new SiteMapParser(sitemapStrict, sitemapPartial);
        boolean sitemapStrictNamespace = new Boolean(System.getProperty("sitemap.strictNamespace"));
        parser.setStrictNamespace(sitemapStrictNamespace);
        boolean sitemapLazyNamespace = new Boolean(System.getProperty("sitemap.lazyNamespace"));
        if (sitemapLazyNamespace) {
            parser.setStrictNamespace(true);
            parser.addAcceptedNamespace(Namespace.SITEMAP_LEGACY);
            parser.addAcceptedNamespace(Namespace.EMPTY);
        }
        if (test.enableSitemapExtensions) {
            parser.enableExtensions();
            parser.setStrictNamespace(true);
        }
        LOG.info("Using {}", parser.getClass());

        test.run(parser, args);
    }

}
