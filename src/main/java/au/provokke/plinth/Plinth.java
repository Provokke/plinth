package au.provokke.plinth;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.CRC32;

public final class Plinth implements AutoCloseable {
    private static final String LOG_NAME = "plinth.log";
    private static final String COMPACT_NAME = "plinth.compact";
    private static final int HEADER_SIZE = 12;
    private static final int TOMBSTONE = -1;

    private record Entry(long valueOffset, int valueLength) {}

    private final Path directory;
    private final Path logPath;
    private final Map<String, Entry> keyDir = new HashMap<>();
    private RandomAccessFile log;
    private long garbageBytes;

    private Plinth(Path directory) {
        this.directory = directory;
        this.logPath = directory.resolve(LOG_NAME);
    }

    public static Plinth open(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve(COMPACT_NAME));
        Plinth store = new Plinth(directory);
        store.log = new RandomAccessFile(store.logPath.toFile(), "rw");
        try {
            store.recover();
        } catch (IOException e) {
            store.log.close();
            throw e;
        }
        return store;
    }

    public synchronized void put(String key, byte[] value) throws IOException {
        long valueOffset = append(key, value);
        Entry previous = keyDir.put(key, new Entry(valueOffset, value.length));
        if (previous != null) {
            garbageBytes += recordSize(key, previous.valueLength());
        }
    }

    public synchronized byte[] get(String key) throws IOException {
        Entry entry = keyDir.get(key);
        if (entry == null) {
            return null;
        }
        return readValue(entry);
    }

    public synchronized boolean delete(String key) throws IOException {
        Entry previous = keyDir.remove(key);
        if (previous == null) {
            return false;
        }
        append(key, null);
        garbageBytes += recordSize(key, previous.valueLength()) + recordSize(key, 0);
        return true;
    }

    public synchronized boolean contains(String key) {
        return keyDir.containsKey(key);
    }

    public synchronized Set<String> keys() {
        return new TreeSet<>(keyDir.keySet());
    }

    public synchronized int size() {
        return keyDir.size();
    }

    public synchronized long garbageBytes() {
        return garbageBytes;
    }

    public synchronized void compact() throws IOException {
        Path compactPath = directory.resolve(COMPACT_NAME);
        Map<String, Entry> rebuilt = new HashMap<>();
        try (RandomAccessFile out = new RandomAccessFile(compactPath.toFile(), "rw")) {
            out.setLength(0);
            for (String key : new TreeSet<>(keyDir.keySet())) {
                byte[] value = readValue(keyDir.get(key));
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                long start = out.getFilePointer();
                out.write(encode(keyBytes, value));
                rebuilt.put(key, new Entry(start + HEADER_SIZE + 4 + keyBytes.length, value.length));
            }
            out.getFD().sync();
        }
        log.close();
        Files.move(compactPath, logPath, StandardCopyOption.REPLACE_EXISTING);
        log = new RandomAccessFile(logPath.toFile(), "rw");
        keyDir.clear();
        keyDir.putAll(rebuilt);
        garbageBytes = 0;
    }

    @Override
    public synchronized void close() throws IOException {
        log.close();
    }

    private byte[] readValue(Entry entry) throws IOException {
        byte[] value = new byte[entry.valueLength()];
        log.seek(entry.valueOffset());
        log.readFully(value);
        return value;
    }

    private long append(String key, byte[] value) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        long start = log.length();
        log.seek(start);
        log.write(encode(keyBytes, value));
        return start + HEADER_SIZE + 4 + keyBytes.length;
    }

    private static byte[] encode(byte[] key, byte[] value) {
        int valueLength = value == null ? TOMBSTONE : value.length;
        int payload = key.length + (value == null ? 0 : value.length);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + 4 + payload);
        buffer.putInt(0);
        buffer.putInt(key.length);
        buffer.putInt(valueLength);
        buffer.putInt(0);
        buffer.put(key);
        if (value != null) {
            buffer.put(value);
        }
        byte[] bytes = buffer.array();
        CRC32 crc = new CRC32();
        crc.update(bytes, 4, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(0, (int) crc.getValue());
        return bytes;
    }

    private static long recordSize(String key, int valueLength) {
        return HEADER_SIZE + 4 + key.getBytes(StandardCharsets.UTF_8).length + valueLength;
    }

    private void recover() throws IOException {
        long length = log.length();
        long position = 0;
        keyDir.clear();
        garbageBytes = 0;
        int headerLength = HEADER_SIZE + 4;
        while (position < length) {
            if (length - position < headerLength) {
                log.setLength(position);
                return;
            }
            byte[] header = new byte[headerLength];
            log.seek(position);
            log.readFully(header);
            ByteBuffer view = ByteBuffer.wrap(header);
            int storedCrc = view.getInt();
            int keyLength = view.getInt();
            int valueLength = view.getInt();
            if (keyLength < 0 || valueLength < TOMBSTONE) {
                throw new CorruptLogException("invalid record header at offset " + position);
            }
            long payloadLength = (long) keyLength + Math.max(valueLength, 0);
            if (position + headerLength + payloadLength > length) {
                log.setLength(position);
                return;
            }
            byte[] record = new byte[(int) (headerLength + payloadLength)];
            log.seek(position);
            log.readFully(record);
            CRC32 crc = new CRC32();
            crc.update(record, 4, record.length - 4);
            if ((int) crc.getValue() != storedCrc) {
                throw new CorruptLogException("checksum mismatch at offset " + position);
            }
            String key = new String(record, headerLength, keyLength, StandardCharsets.UTF_8);
            if (valueLength == TOMBSTONE) {
                Entry previous = keyDir.remove(key);
                garbageBytes += record.length + (previous == null ? 0 : recordSize(key, previous.valueLength()));
            } else {
                Entry previous = keyDir.put(key, new Entry(position + headerLength + keyLength, valueLength));
                if (previous != null) {
                    garbageBytes += recordSize(key, previous.valueLength());
                }
            }
            position += record.length;
        }
    }
}
