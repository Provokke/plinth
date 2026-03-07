package au.provokke.plinth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlinthTest {
    @TempDir
    Path dir;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetOverwriteAndDelete() throws IOException {
        try (Plinth store = Plinth.open(dir)) {
            store.put("a", bytes("one"));
            store.put("b", bytes("two"));
            store.put("a", bytes("uno"));
            assertArrayEquals(bytes("uno"), store.get("a"));
            assertArrayEquals(bytes("two"), store.get("b"));
            assertNull(store.get("missing"));
            assertTrue(store.delete("b"));
            assertFalse(store.delete("b"));
            assertNull(store.get("b"));
            assertEquals(Set.of("a"), store.keys());
        }
    }

    @Test
    void emptyValueIsDistinctFromDelete() throws IOException {
        try (Plinth store = Plinth.open(dir)) {
            store.put("k", new byte[0]);
            assertArrayEquals(new byte[0], store.get("k"));
            assertTrue(store.contains("k"));
        }
    }

    @Test
    void reopenReplaysLog() throws IOException {
        try (Plinth store = Plinth.open(dir)) {
            store.put("a", bytes("1"));
            store.put("b", bytes("2"));
            store.put("a", bytes("3"));
            store.delete("b");
        }
        try (Plinth store = Plinth.open(dir)) {
            assertArrayEquals(bytes("3"), store.get("a"));
            assertNull(store.get("b"));
            assertEquals(1, store.size());
            assertTrue(store.garbageBytes() > 0);
        }
    }

    @Test
    void flippedByteIsDetected() throws IOException {
        try (Plinth store = Plinth.open(dir)) {
            store.put("key", bytes("value"));
        }
        try (RandomAccessFile raf = new RandomAccessFile(dir.resolve("plinth.log").toFile(), "rw")) {
            raf.seek(raf.length() - 1);
            int last = raf.read();
            raf.seek(raf.length() - 1);
            raf.write(last ^ 0xFF);
        }
        assertThrows(CorruptLogException.class, () -> Plinth.open(dir));
    }

    @Test
    void truncatedTailIsDiscarded() throws IOException {
        try (Plinth store = Plinth.open(dir)) {
            store.put("first", bytes("kept"));
            store.put("second", bytes("lost"));
        }
        Path log = dir.resolve("plinth.log");
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "rw")) {
            raf.setLength(raf.length() - 3);
        }
        try (Plinth store = Plinth.open(dir)) {
            assertArrayEquals(bytes("kept"), store.get("first"));
            assertNull(store.get("second"));
            store.put("third", bytes("fresh"));
        }
        try (Plinth store = Plinth.open(dir)) {
            assertArrayEquals(bytes("fresh"), store.get("third"));
        }
    }

    @Test
    void compactionShrinksLogAndKeepsData() throws IOException {
        Path log = dir.resolve("plinth.log");
        try (Plinth store = Plinth.open(dir)) {
            for (int i = 0; i < 50; i++) {
                store.put("hot", bytes("value-" + i));
            }
            store.put("cold", bytes("stay"));
            store.put("gone", bytes("bye"));
            store.delete("gone");
            long before = Files.size(log);
            store.compact();
            assertTrue(Files.size(log) < before);
            assertEquals(0, store.garbageBytes());
            assertArrayEquals(bytes("value-49"), store.get("hot"));
            assertArrayEquals(bytes("stay"), store.get("cold"));
            assertNull(store.get("gone"));
            store.put("after", bytes("ok"));
        }
        try (Plinth store = Plinth.open(dir)) {
            assertEquals(Set.of("hot", "cold", "after"), store.keys());
            assertArrayEquals(bytes("value-49"), store.get("hot"));
            assertArrayEquals(bytes("ok"), store.get("after"));
        }
    }
}

