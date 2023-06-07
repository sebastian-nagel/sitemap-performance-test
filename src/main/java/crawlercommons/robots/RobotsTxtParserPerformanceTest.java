/**
 * Copyright 2023 Crawler-Commons
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

package crawlercommons.robots;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import crawlercommons.robots.SimpleRobotRules.RobotRule;
import crawlercommons.warcutils.WarcTestProcessor;

public class RobotsTxtParserPerformanceTest extends WarcTestProcessor {

    private static Logger LOG = LoggerFactory.getLogger(RobotsTxtParserPerformanceTest.class);

    protected Counter counter = new Counter();

    protected String urlToBeParsed = System.getProperty("warc.parse.url");

    protected String robotName;

    protected class ArchiveRecordRobotsTxtParser implements ArchiveRecordProcessor {

        private BaseRobotsParser parser;
        private Set<String> acceptedUrls = new HashSet<>();

        public ArchiveRecordRobotsTxtParser(BaseRobotsParser parser) {
            this.parser = parser;
        }

        public void filterAllowUrl(String url) {
            acceptedUrls.add(url);
        }

        @Override
        public void process(WarcRecord record, long offset) {
            if (!(record instanceof WarcResponse)) {
                return;
            }
            WarcResponse response = ((WarcResponse) record);
            String url = response.target();
            if (!acceptedUrls.isEmpty() && !acceptedUrls.contains(url)) {
                return;
            }
            try {
                Record warcRecord = new Record(response, offset);
                byte[] content = getContent(response);
                processRecord(parser, url, warcRecord, content);
            } catch (IOException | IllegalArgumentException e) { // TODO: remove IllegalArgumentException (jwarc#38)
                LOG.error("Failed to process WARC record " + url, e);
                counter.failedFetch++;
            }
        }
    }

    protected class Counter extends WarcTestProcessor.Counter {
        int failedParse = 0;
        int numRobotsRulesSets;
        int allowedAll = 0;
        int allowedNone = 0;
        int allowedPartial = 0;
        int hasSitemaps = 0;
        int numSitemaps = 0;

        public void countRules(BaseRobotRules rules) {
            List<RobotRule> rulesList = null;
            if (rules instanceof SimpleRobotRules) {
                rulesList = ((SimpleRobotRules) rules).getRobotRules();
            }
            numRobotsRulesSets += 1;
            if (rules.isAllowAll()) {
                allowedAll += 1;
            } else if (rules.isAllowNone()) {
                allowedNone += 1;
            } else if (rulesList.size() == 1 && rulesList.get(0).getPrefix().equals("/")) {
                if (rulesList.get(0).isAllow())
                    allowedAll += 1;
                else
                    allowedNone += 1;
            } else {
                allowedPartial += 1;
            }
            List<String> sitemaps = rules.getSitemaps();
            if (!sitemaps.isEmpty()) {
                hasSitemaps += 1;
                numSitemaps += sitemaps.size();
            }
        }

        @Override
        public void log(Logger log) {
            super.log(log);
            log.info("{}\tfailed to parse robots.txt", f(failedParse));
            log.info("{}\t{}\trobots.txt allowed all", f(allowedAll), fPercent(allowedAll, processed));
            log.info("{}\t{}\trobots.txt allowed none", f(allowedNone), fPercent(allowedNone, processed));
            log.info("{}\t{}\trobots.txt allowed partial", f(allowedPartial), fPercent(allowedPartial, processed));
            log.info("{}\t{}\trobots.txt with sitemaps", f(hasSitemaps), fPercent(hasSitemaps, processed));
            log.info("{}\ttotal number of sitemap URLs found", f(numSitemaps));
        }
    }

    protected void processRecord(BaseRobotsParser parser, String urlString, Record record, byte[] content) {
        LOG.debug("Processing robots.txt {}", urlString);
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
            // TODO: call parser.failedFetch(record.status)
            LOG.warn("Failed to fetch {} (HTTP status = {})", urlString, record.status);
            counter.failedFetch++;
            return;
        }
        counter.processed++;
        if (content == null) {
            try {
                content = record.getContent();
            } catch (IOException e) {
                LOG.error("Failed to get record for {}: {}", urlString, record);
                return;
            }
        }
        BaseRobotRules rules;
        long start = System.currentTimeMillis();
        try {
            LOG.debug("Parsing robots.txt {}", urlString);
            rules = parser.parseContent(urlString, content, record.contentType, robotName);
            LOG.debug(rules.toString());
            counter.countRules(rules);
        } finally {
            long elapsed = (System.currentTimeMillis() - start);
            if (elapsed > 150) {
                LOG.warn("Processing robots.txt {} took {}ms", urlString, elapsed);
            }
            counter.elapsed += elapsed;
        }
        // TODO: apply rules to set of URLs
        if ((counter.processed % 50) == 0) {
            LOG.info("Processed {} robots.txt files", counter.processed);
        }
        counter.success++;
    }

    public void run(BaseRobotsParser parser, String[] warcPaths) throws MalformedURLException, IOException {
        if (urlToBeParsed != null) {
            LOG.info("Parsing robots.txt for URL <{}>", this.urlToBeParsed);
        }

        long start = System.currentTimeMillis();

        ArchiveRecordRobotsTxtParser proc = new ArchiveRecordRobotsTxtParser(parser);
        if (urlToBeParsed != null) {
            proc.filterAllowUrl(urlToBeParsed);
        }
        for (String warcPath : warcPaths) {
            readWarcFile(warcPath, proc);
        }

        LOG.info("Finished processing, elapsed: {} ms", (System.currentTimeMillis() - start));
        counter.log(LOG);
    }

    public static void main(String[] args) throws MalformedURLException, IOException {

        if (args.length < 1) {
            LOG.error("Usage:  Robots.txt parser test <WARC-file>...");
            LOG.error("Java properties:");
            LOG.error("  robot.name  (String) robot name, \"product token\" as in RFC 9309");
            LOG.error("  warc.parse.url  (String/URL) parse robots.txt of URL only");
            System.exit(1);
        }

        RobotsTxtParserPerformanceTest test = new RobotsTxtParserPerformanceTest();

        String robotName = System.getProperty("robot.name");
        if (robotName == null || robotName.isBlank()) {
            robotName = "*";
        }
        test.robotName = robotName;
        BaseRobotsParser parser = new SimpleRobotRulesParser();
        LOG.info("Parsing robots.txt files for robot \"{}\" using {} (crawler-commons v{})", robotName, parser.getClass(), crawlercommons.CrawlerCommons.getVersion());

        test.run(parser, args);
    }

}
