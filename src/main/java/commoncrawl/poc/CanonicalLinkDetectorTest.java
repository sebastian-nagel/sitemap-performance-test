/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package commoncrawl.poc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HeaderElement;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeaderValueParser;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import crawlercommons.warcutils.WarcTestProcessor;

public class CanonicalLinkDetectorTest extends WarcTestProcessor {

    private static Logger LOG = LoggerFactory.getLogger(CanonicalLinkDetectorTest.class);

    protected static Set<String> SUPPORTED_CONTENT_TYPES = new HashSet<>();
    static {
        SUPPORTED_CONTENT_TYPES.add("text/html");
        SUPPORTED_CONTENT_TYPES.add("application/xhtml+xml");
    }

    /**
     * Pattern to match canonical link elements in HTML. The length of the
     * canonical link URL inside the element is limited to max. 2048 characters.
     */
    private static Pattern canonicalLinkPattern = Pattern.compile("<link\\s+[^>]{0,2054}rel=(?:'canonical'|\"canonical\"|canonical\\b)[^>]{0,2054}>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static Pattern hrefPattern = Pattern.compile("href=['\"]?([^'\"\\s]{0,2048})", Pattern.CASE_INSENSITIVE);

    private static Pattern canonicalRelValuePattern = Pattern.compile("\\bcanonical\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern linkInParentheses = Pattern.compile("^\\s*<\\s*(.*?)\\s*>\\s*$");

    private static final List<String> EMPTY_RESULT = new ArrayList<>(0);

    protected class Counter extends WarcTestProcessor.Counter {
        int canonicalLinksFound = 0;
        int canonicalLinksFoundHttp = 0;
        int canonicalLinksFoundHtml = 0;
        TreeMap<Integer,Integer> uniqCounts = new TreeMap<>();

        @Override
        public void log(Logger log) {
            super.log(log);
            log.info("{}\tcanonical links found", f(canonicalLinksFound));
            log.info("{}\tcanonical links found in HTTP headers", f(canonicalLinksFoundHttp));
            log.info("{}\tcanonical links found in HTML", f(canonicalLinksFoundHtml));
            for (int size : uniqCounts.keySet()) {
                log.info("{}\t{} unique canonical links", f(uniqCounts.get(size)), size);
            }
        }

        public void uniqUpdate(List<String> canonicalLinks) {
            int uniq = Set.of(canonicalLinks).size();
            uniqCounts.put(uniq, uniqCounts.getOrDefault(uniq, 0) + 1);
        }
    }

    protected Counter counter = new Counter();

    protected class ArchiveRecordCanonicalLinkDetector implements ArchiveRecordProcessor {
        private boolean useByteArrayCharSequence;
        /** top-N bytes of HTML to look for canonical link */
        private int chunkSize = 8192;
        private int maxLinks = 1;

        /**
         * Extract canonical link from HTTP header.
         * 
         * The extraction is delegated to {@link BasicHeaderValueParser} because
         * parsing multi-valued link attributes is far from trivial, e.g.
         * 
         * <pre>
         Link: <https://frontera.library.ucla.edu/songs>; rel="canonical",<https://frontera.library.ucla.edu/songs>; rel="shortlink",<https://frontera.library.ucla.edu/favicon.ico>; rel="shortcut icon"
         * </pre>
         * 
         * @param &quot;Link&quot;
         *            header values
         * @return the canonical links found, or an empty list if no canonical
         *         link is found
         */
        protected List<String> detectCanonicalLinksHttpHeader(List<String> headerValues, int maxResults) {
            List<String> result = EMPTY_RESULT;
            for (String httpHeaderLink : headerValues) {
                HeaderElement elem = BasicHeaderValueParser.parseHeaderElement(httpHeaderLink, BasicHeaderValueParser.INSTANCE);
                for (NameValuePair param : elem.getParameters()) {
                    if ("rel".equalsIgnoreCase(param.getName()) && canonicalRelValuePattern.matcher(param.getValue()).find()) {
                        String link = elem.getName();
                        // match inside < ... >
                        Matcher urlMatcher = linkInParentheses.matcher(link);
                        if (urlMatcher.matches()) {
                            link = urlMatcher.group(1);
                            if (result == EMPTY_RESULT) {
                                result = new ArrayList<String>(1);
                            }
                            result.add(link);
                            if (result.size() >= maxResults) {
                                break;
                            }
                        }
                    }
                }
            }
            return result;
        }

        public boolean isEligibleContentType(String contentType) {
            return SUPPORTED_CONTENT_TYPES.contains(contentType);
        }

        /**
         * Extract canonical link from HTTP header.
         * 
         * The extraction is delegated to {@link BasicHeaderValueParser} because
         * parsing multi-valued link attributes is far from trivial, e.g.
         * 
         * <pre>
         Link: <https://frontera.library.ucla.edu/songs>; rel="canonical",<https://frontera.library.ucla.edu/songs>; rel="shortlink",<https://frontera.library.ucla.edu/favicon.ico>; rel="shortcut icon"
         * </pre>
         * 
         * @param &quot;Link&quot;
         *            header values
         * @return the canonical links found, or an empty list if no canonical
         *         link is found
         */
        public List<String> detectCanonicalLinksHTML(byte[] content, int chunkSize, int maxResults) {
            List<String> result = EMPTY_RESULT;
            int length = content.length < chunkSize ? content.length : chunkSize;
            CharSequence cs;
            if (useByteArrayCharSequence) {
                cs = new ByteArrayCharSequence(content, length);
            } else {
                cs = new String(content, 0, length, StandardCharsets.US_ASCII);
            }
            Matcher clMatcher = canonicalLinkPattern.matcher(cs);
            while (clMatcher.find()) {
                CharSequence cls;
                if (useByteArrayCharSequence) {
                    cls = cs.subSequence(clMatcher.start(), clMatcher.end());
                } else {
                    cls = clMatcher.group();
                }
                Matcher hrefMatcher = hrefPattern.matcher(cls);
                if (hrefMatcher.find(5)) {
                    String cl = hrefMatcher.group(1);
                    if (result == EMPTY_RESULT) {
                        result = new ArrayList<String>(1);
                    }
                    result.add(cl);
                    if (result.size() >= maxResults) {
                        break;
                    }
                }
            }
            return result;
        }

        @Override
        public void process(WarcRecord record, long offset) {
            if (!(record instanceof WarcResponse)) {
                return;
            }
            counter.processed++;
            WarcResponse response = ((WarcResponse) record);
            String url = response.target();
            List<String> canonicalLinks;
            boolean foundCanonicalLinks = false;
            try {
                Record warcRecord = new Record(response, offset);
                canonicalLinks = detectCanonicalLinksHttpHeader(warcRecord.httpHeaders.all("Link"), maxLinks);
                if (!canonicalLinks.isEmpty()) {
                    System.out.print(response.target());
                    for (String cl : canonicalLinks) {
                        System.out.print('\t');
                        System.out.print(cl);
                    }
                    System.out.print('\n');
                    foundCanonicalLinks = true;
                    counter.canonicalLinksFound += canonicalLinks.size();
                    counter.canonicalLinksFoundHttp += canonicalLinks.size();
                    if (canonicalLinks.size() >= maxLinks) {
                        counter.success++;
                        counter.uniqUpdate(canonicalLinks);
                        return;
                    }
                }

                String contentType = warcRecord.header.first("WARC-Identified-Payload-Type").orElse(warcRecord.contentType);
                if (!isEligibleContentType(contentType)) {
                    if (foundCanonicalLinks) {
                        counter.success++;
                    }
                    return;
                }

                byte[] content = getContent(response);
                long start = System.currentTimeMillis();
                canonicalLinks = detectCanonicalLinksHTML(content, chunkSize, maxLinks);
                long elapsed = (System.currentTimeMillis() - start);
                if (elapsed > 150) {
                    LOG.warn("Detecting canonical links {} took {}ms", response.target(), elapsed);
                }
                counter.elapsed += elapsed;
                if (!canonicalLinks.isEmpty()) {
                    foundCanonicalLinks = true;
                    counter.canonicalLinksFound += canonicalLinks.size();
                    counter.canonicalLinksFoundHtml += canonicalLinks.size();
                    System.out.print(response.target());
                    for (String cl : canonicalLinks) {
                        System.out.print('\t');
                        System.out.print(cl);
                    }
                    System.out.print('\n');
                }
                if (foundCanonicalLinks) {
                    counter.success++;
                    counter.uniqUpdate(canonicalLinks);
                }
            } catch (IOException | IllegalArgumentException e) {
                LOG.error("Failed to process WARC record " + url, e);
            }
        }

        public void useByteArrayCharSequence(boolean val) {
            useByteArrayCharSequence = val;
        }

        public void setChunkSize(int size) {
            chunkSize = size;
        }

        public void setMaxResults(Integer n) {
            maxLinks = n;
        }
    }

    public void run(String[] warcPaths) throws IOException {
        ArchiveRecordCanonicalLinkDetector proc = new ArchiveRecordCanonicalLinkDetector();
        proc.useByteArrayCharSequence(Boolean.valueOf(System.getProperty("useByteArrayCharSequence")));
        proc.setChunkSize(Integer.valueOf(System.getProperty("chunkSize", "8192")));
        proc.setMaxResults(Integer.valueOf(System.getProperty("maxResults", "1")));
        long start = System.currentTimeMillis();
        for (String warcPath : warcPaths) {
            try {
                readWarcFile(warcPath, proc);
            } catch (IOException e) {
                LOG.error("Failure reading WARC file {}", warcPath, e);
                throw e;
            }
        }
        LOG.info("Finished processing, elapsed: {} ms", (System.currentTimeMillis() - start));
        counter.log(LOG);
    }

    public static void main(String[] args) throws IOException {
        CanonicalLinkDetectorTest tester = new CanonicalLinkDetectorTest();
        tester.run(args);
    }

}
