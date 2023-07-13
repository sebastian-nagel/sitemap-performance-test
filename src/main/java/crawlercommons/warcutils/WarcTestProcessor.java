package crawlercommons.warcutils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.netpreserve.jwarc.MessageBody;
import org.netpreserve.jwarc.MessageHeaders;
import org.netpreserve.jwarc.WarcPayload;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcTargetRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class WarcTestProcessor {

    protected static final long MAX_PAYLOAD_SIZE = 128 * 1048576;
    protected static final int BUFFER_SIZE = 8192;

    private static Logger LOG = LoggerFactory.getLogger(WarcTestProcessor.class);

    protected Map<String,Record> records = new LinkedHashMap<>();
    protected List<String> warcFiles = new ArrayList<>();
    protected List<FileChannel> warcChannels = new ArrayList<>();

    protected enum ContentEncoding { NOT_SUPPORTED, IDENTITY, GZIP, DEFLATE };

    protected class Record {
        public long offset;
        public int status;
        public int warcFileId;
        public boolean isProcessed = false;
        public String contentType;
        public MessageHeaders header;
        public MessageHeaders httpHeaders;

        private void parseHttpHeader(WarcResponse record) throws IOException {
            httpHeaders = record.http().headers();
            status = record.http().status();
            contentType = httpHeaders.first("Content-Type").orElse(null);
        }

        public Record(WarcResponse record, long offset) throws IOException {
            header = record.headers();
            this.offset = offset;
            parseHttpHeader(record);
        }

        public byte[] getContent() throws IOException {
            FileChannel channel = warcChannels.get(warcFileId);
            channel.position(offset);
            try (WarcReader warcReader = new WarcReader(channel)) {
                Optional<WarcRecord> record = warcReader.next();
                if (record.isPresent()) {
                    return WarcTestProcessor.getContent((WarcResponse) record.get());
                }
            }
            throw new IOException("No Warc response record at offset " + offset);
        }


        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("warc-file-id=").append(warcFileId);
            sb.append(", offset=").append(offset);
            sb.append(", status=").append(status);
            return sb.toString();
        }
    }

    private static ContentEncoding getContentEncoding(WarcResponse record) throws IOException {
        List<String> contentEncodings = record.http().headers().all("Content-Encoding");
        if (contentEncodings.isEmpty()) {
            return ContentEncoding.IDENTITY;
        } else if (contentEncodings.size() > 1) {
            LOG.warn("Multiple Content-Encodings not supported: {}", contentEncodings);
            return ContentEncoding.NOT_SUPPORTED;
        }
        switch (contentEncodings.get(0).toLowerCase(Locale.ROOT)) {
            case "identity":
            case "none":
            case "":
                return ContentEncoding.IDENTITY;
            case "gzip":
            case "x-gzip":
                return ContentEncoding.GZIP;
            case "deflate":
                return ContentEncoding.DEFLATE;
        }
        LOG.warn("Unknown/unsupported Content-Encoding: {}", contentEncodings.get(0));
        return ContentEncoding.NOT_SUPPORTED;
    }

    public static byte[] getContent(WarcResponse record) throws IOException {
        return getContent(record, MAX_PAYLOAD_SIZE);
    }

    public static byte[] getContent(WarcResponse record, long maxSize) throws IOException {
        Optional<WarcPayload> payload = record.payload();
        if (!payload.isPresent()) {
            return new byte[0];
        }
        MessageBody body = payload.get().body();
        long size = body.size();
        if (size > maxSize) {
            throw new IOException("WARC payload too large");
        }
        // Wrap channel to decode/uncompress Content-Encoding
        ReadableByteChannel bodyChan = body;
        ContentEncoding contentEncoding = getContentEncoding(record);
        switch (contentEncoding) {
            case IDENTITY:
                break;
            case GZIP:
                size = -1;
                bodyChan = org.netpreserve.jwarc.IOUtils.gunzipChannel(body);
                break;
            case DEFLATE:
                size = -1;
                bodyChan = org.netpreserve.jwarc.IOUtils.inflateChannel(body);
                break;
            case NOT_SUPPORTED:
                // even if unsupported: try to parse the content
                break;
        }
        ByteBuffer buf;
        if (size >= 0) {
            buf = ByteBuffer.allocate((int) size);
        } else {
            buf = ByteBuffer.allocate(BUFFER_SIZE);
        }
        /** dynamically growing list of buffers for large content of unknown size */
        ArrayList<ByteBuffer> bufs = new ArrayList<>();
        int r, read = 0;
        while (true) {
            try {
                if ((r = bodyChan.read(buf)) < 0) break;
            } catch (Exception e) {
                LOG.error("Failed to read content of {}: {}", record.target(), e);
                break;
            }
            if (r == 0 && !buf.hasRemaining()) {
                buf.flip();
                bufs.add(buf);
                buf = ByteBuffer.allocate(BUFFER_SIZE);
            }
            read += r;
        }
        buf.flip();
        if (read == size) {
            return buf.array();
        }
        byte[] arr = new byte[read];
        int pos = 0;
        for (ByteBuffer b :bufs) {
            r = b.remaining();
            b.get(arr, pos, r);
            pos += r;
        }
        buf.get(arr, pos, buf.remaining());
        return arr;
    }

    public void readWarcFile(String warcPath, ArchiveRecordProcessor proc) throws MalformedURLException, IOException {
        FileChannel channel = FileChannel.open(Paths.get(warcPath));
        warcFiles.add(warcPath);
        warcChannels.add(channel);
        try (WarcReader reader = new WarcReader(channel)) {
            int records = 0;
            for (WarcRecord record : reader) {
                if (!(record instanceof WarcResponse)) {
                    continue;
                }
                records++;
                proc.process(record, reader.position());
                if ((records % 1000) == 0) {
                    LOG.info("Read {} WARC response records", records);
                }
            }
            LOG.info("Read {} WARC response records from file {}", records, warcPath);
        }
    }

    public Record getRecord(String url) {
        return records.get(url);
    }

    protected interface ArchiveRecordProcessor {

        public void process(WarcRecord record, long offset);

        /** Get URL, trimming <code>&lt;...&gt;</code> if needed */
        public default String getUrl(WarcRecord record) {
            if (!(record instanceof WarcTargetRecord)) {
                return null;
            }
            return ((WarcTargetRecord) record).target();
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
        public void process(WarcRecord record, long offset) {
            if (!(record instanceof WarcResponse)) {
                return;
            }
            String url = ((WarcTargetRecord) record).target();
            try {
                Record warcRecord = new Record((WarcResponse) record, offset);
                warcRecord.warcFileId = this.warcId;
                records.put(url, warcRecord);
            } catch(IOException e) {
                LOG.error("Failed to process WARC record " + url, e);
                records.put(url, null);
            }
        }
    }

    protected class Counter {
        public int processed = 0;
        public int failedFetch = 0;
        public int success = 0;
        public long elapsed = 0;

        protected String f(long n) {
            return String.format(Locale.ROOT, "%8d", n);
        }

        protected String fPercent(long n, long N) {
            return String.format(Locale.ROOT, "%6.2f%%", 100.0 * n / N);
        }

        public void log(Logger log) {
            log.info("{}\tdocuments processed total", f(processed));
            log.info("{}\tsuccessfully processed", f(success));
            log.info("{}\tfailed to process", f(processed - success));
            log.info("{}\tfailed to fetch document", f(failedFetch));
            log.info("{}\ttotal time elapsed", f(elapsed));
        }
    }

}
