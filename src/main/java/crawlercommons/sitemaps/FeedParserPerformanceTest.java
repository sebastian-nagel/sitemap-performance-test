package crawlercommons.sitemaps;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;

public class FeedParserPerformanceTest extends SiteMapPerformanceTest {

    private static Logger LOG = LoggerFactory.getLogger(FeedParserPerformanceTest.class);

    public static class FeedParser extends SiteMapParser {

        private DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).withZone(ZoneId.of(ZoneOffset.UTC.toString()));;

        public AbstractSiteMap parseSiteMap(byte[] content, URL url) throws UnknownFormatException {

            FeedParserPerformanceTest.LOG.info("Parsing feed: {}", url);
            SyndFeed feed = null;
            try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
                SyndFeedInput input = new SyndFeedInput();
                feed = input.build(new InputStreamReader(is, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException | FeedException | IOException e) {
                String msg = "Failed to parse " + url + ": " + e.getMessage();
                FeedParserPerformanceTest.LOG.warn(msg);
                throw new UnknownFormatException(msg);
            }

            SiteMap sitemap = new SiteMap(url);
            FeedParserPerformanceTest.LOG.info("... of type {}", feed.getFeedType());
            if (feed.getFeedType().startsWith("rss")) {
                sitemap.setType(AbstractSiteMap.SitemapType.RSS);
            } else if (feed.getFeedType().startsWith("atom")) {
                sitemap.setType(AbstractSiteMap.SitemapType.ATOM);
            }

            for (SyndEntry entry : feed.getEntries()) {
                String target = entry.getLink();
                if (target == null || target.trim().isEmpty()) {
                    target = entry.getUri();
                    if (target == null || target.trim().isEmpty()) {
                        continue;
                    }
                }
                URL tUrl;
                try {
                    tUrl = new URL(url, target);
                } catch (MalformedURLException e) {
                    FeedParserPerformanceTest.LOG.error("Invalid link {}: {}", target, e);
                    continue;
                }
                Date updatedDate = entry.getUpdatedDate();
                Date publishedDate = entry.getPublishedDate();
                Date date = null;
                if (updatedDate != null) {
                    date = updatedDate;
                } else if (publishedDate != null) {
                    date = publishedDate;
                }
                String lastMod = null;
                if (date != null) {
                    lastMod = dateFormatter.format(date.toInstant());
                }

                sitemap.addSiteMapUrl(new SiteMapURL(tUrl.toString(), lastMod, null, null, true));
            }

            return sitemap;
        }

    }

    public static void main(String[] args) throws MalformedURLException, IOException {

        if (args.length < 1) {
            LOG.error("Usage:  FeedParserPerformanceTest <WARC-file>...");
            LOG.error("Java properties:");
            LOG.error("  warc.index      (boolean) index WARC files and parse sitemap indexes recursively");
            LOG.error("  warc.parse.url  (String/URL) parse sitemap indexed by URL");
            LOG.error("                            (recursively if it's a sitemap index and warc.index is true)");
            System.exit(1);
        }

        SiteMapParser parser = new FeedParser();
        boolean sitemapStrictNamespace = Boolean.valueOf(System.getProperty("sitemap.strictNamespace"));
        parser.setStrictNamespace(sitemapStrictNamespace);
        LOG.info("Using {}", parser.getClass());

        SiteMapPerformanceTest test = new FeedParserPerformanceTest();

        test.run(parser, args);
    }

}
