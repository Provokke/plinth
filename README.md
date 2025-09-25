<div align="center">

# plinth

An embedded, append-only key-value store for Java 21, modelled on Bitcask. No dependencies beyond the JDK.

![License](https://img.shields.io/badge/LICENSE-MIT-blue?style=for-the-badge)  ![Language](https://img.shields.io/badge/JAVA-ed8b00?style=for-the-badge&logo=openjdk&logoColor=white)  ![Tests](https://img.shields.io/badge/TESTS-6-brightgreen?style=for-the-badge)

Refer to [LICENSE](LICENSE) for the legal bits.

</div>

## What's in it

- **An append-only log.** Every write is appended to a single file as a record with a CRC32 checksum. Deletes append a tombstone.
- **An in-memory key directory** that maps each live key to the offset of its latest value, so a read is one seek and one read.
- **Recovery on open.** The log is replayed to rebuild the directory. A checksum mismatch raises `CorruptLogException`, while a partial record at the very end, as left by a crash mid-write, is truncated.
- **`compact()`**, which rewrites the log with only live records and syncs the result before swapping it in.
- A small API: `put`, `get`, `delete`, `contains`, `keys`, `size` and `garbageBytes`.

Keys are strings stored as UTF-8. Values are byte arrays. `get` returns `null` for a missing key, and an empty value is distinct from a delete.

```java
try (Plinth store = Plinth.open(Path.of("data"))) {
    store.put("greeting", "hello".getBytes(StandardCharsets.UTF_8));
    byte[] value = store.get("greeting");
    store.delete("greeting");
    store.compact();
}
```

## 🏗️ Architecture

```
 Plinth.open(dir)
   │  deletes any stale plinth.compact, opens plinth.log, recover()
   v
 ┌────────────────────────────┐        ┌───────────────────────────────┐
 │ keyDir: Map<String, Entry> │ ─────> │ plinth.log                    │
 │   Entry(valueOffset,       │  seek  │ [crc32|keyLen|valLen|0|key|val]│
 │         valueLength)       │        │ [crc32|keyLen|  -1  |0|key]   │
 └────────────────────────────┘        │  ... appended forever         │
        garbageBytes counter           └───────────────────────────────┘
                                                    │ compact()
                                                    v
                                        plinth.compact ─ sync ─ move over log

 src/main/java/au/provokke/plinth/Plinth.java  CorruptLogException.java
 src/test/java/au/provokke/plinth/PlinthTest.java   pom.xml
```

- The record layout is a CRC32, key length, value length and a reserved word (four bytes each), then the key and value bytes. A value length of -1 marks a tombstone. The checksum covers everything after itself.
- `garbageBytes` tracks how much of the log is overwritten or deleted data, so you can decide when compaction is worth it.
- All keys live in memory, so the key count is bounded by heap size. Values stay on disk.
- Methods are `synchronized` on the store, which is simple but serialises readers.
- Writes aren't fsynced individually, so a power failure can lose recent writes. Compaction does sync its output before replacing the log.
- Compaction rewrites the whole live set and blocks other operations while it runs.

## 🚀 Quick start

```
mvn test
mvn install
```

Needs JDK 21. The only dependency is JUnit 5, and only for tests.

## 🧪 Testing

6 tests, all passing (`Tests run: 6, Failures: 0, Errors: 0`), each against a fresh temporary directory:

- Put, overwrite, get, missing key, delete twice and the key set.
- An empty value is kept and is distinct from a delete.
- Reopening replays the log, including overwrites and deletes, and reports garbage.
- A flipped byte in the last record is caught as `CorruptLogException`.
- A truncated tail record is discarded, earlier records survive and the store accepts new writes after.
- Compaction shrinks the log, zeroes the garbage count, keeps live data and survives a reopen.

## Licence

MIT.

