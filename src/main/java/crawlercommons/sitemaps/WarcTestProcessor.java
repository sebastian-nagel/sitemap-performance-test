package crawlercommons.sitemaps;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

public abstract class WarcTestProcessor {

    private static Logger LOG = LoggerFactory.getLogger(WarcTestProcessor.class);

    protected Map<String,WarcRecord> records = new LinkedHashMap<>();
    protected List<String> warcFiles = new ArrayList<>();

    protected class WarcRecord {
        long offset;
        long contentOffset;
        int status;
        int warcFileId;
        boolean isProcessed = false;
        boolean isChunkedTransferEncoding = false;
        String contentType;
        ArchiveRecordHeader header;
        Header[] httpHeaders;

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
            httpHeaders = LaxHttpParser.parseHeaders(record, ARCConstants.DEFAULT_ENCODING);
            for (Header h : httpHeaders) {
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
            header = record.getHeader();
            offset = header.getOffset();
            status = parseHttpHeader(record);
            contentOffset = record.getPosition()+1;
        }

        public byte[] getContent() throws IOException {
            // must re-open WARC file, no backward seek supported, no forward seek in gzipped WARC files
            WARCReader reader = WARCReaderFactory.get(warcFiles.get(warcFileId));
            reader.setDigest(false);
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
                    String msg = e.getMessage();
                    msg = msg.substring(0, Math.min(120, msg.length()));
                    if (msg.indexOf('\n') != -1) {
                        msg = msg.substring(0, msg.indexOf('\n'));
                    }
                    LOG.warn("Failed to read chunked transfer encoding: {}", msg);
                    // could be an erroneous Transfer-Encoding header
                    return content;
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

    public void readWarcFile(String warcPath, ArchiveRecordProcessor proc) throws MalformedURLException, IOException {
        WARCReader reader = WARCReaderFactory.get(warcPath);
        reader.setDigest(false);
        int records = 0;
        for (ArchiveRecord record : reader) {
            ArchiveRecordHeader header = record.getHeader();
            if (!("application/http;msgtype=response".equals(header.getMimetype()) || "application/http; msgtype=response".equals(header.getMimetype()))) {
                continue;
            }
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

    protected interface ArchiveRecordProcessor {

        public void process(ArchiveRecord record) throws IOException;

        /** Get URL, trimming <code>&lt;...&gt;</code> if needed */
        public default String getUrl(ArchiveRecord record) {
            String url = record.getHeader().getUrl();
            if (url.startsWith("<")) {
                url = url.substring(1, url.length() - 1);
            }
            return url;
        }
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

    protected class Counter {
        int processed = 0;
        int failedFetch = 0;
        int success = 0;

        protected String f(int n) {
            return String.format(Locale.ROOT, "%8d", n);
        }

        public void log(Logger log) {
            log.info("{}\tdocuments processed total", f(processed));
            log.info("{}\tsuccessfully processed", f(success));
            log.info("{}\tfailed to process", f(processed - success));
            log.info("{}\tfailed to fetch document", f(failedFetch));
        }
    }

}
