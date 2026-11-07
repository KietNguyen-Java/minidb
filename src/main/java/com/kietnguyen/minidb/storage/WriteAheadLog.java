package com.kietnguyen.minidb.storage;

import com.kietnguyen.minidb.value.DataType;
import com.kietnguyen.minidb.value.Value;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Append-only log of committed statements. The log is the database file: opening a database
 * replays every record.
 *
 * <p>Record layout: {@code [int payloadLength][long crc32][payload]}. A crash in the middle of an
 * append leaves a short or corrupt last record; {@link #recover} detects it by length or checksum
 * and truncates the file back to the last complete record, so a statement is either fully in the
 * log or not at all.
 */
public final class WriteAheadLog implements Closeable {

    private static final int HEADER_BYTES = Integer.BYTES + Long.BYTES;

    private static final byte CREATE_TABLE = 1;
    private static final byte DROP_TABLE = 2;
    private static final byte CREATE_INDEX = 3;
    private static final byte DROP_INDEX = 4;
    private static final byte INSERT = 5;
    private static final byte DELETE = 6;
    private static final byte UPDATE = 7;

    private static final byte TAG_NULL = 0;
    private static final byte TAG_INT = 1;
    private static final byte TAG_TEXT = 2;

    /** What {@link #recover} found in an existing log. */
    public record Recovery(List<List<LogOp>> records, long discardedBytes) {
    }

    private final Path path;
    private final boolean fsync;
    private FileChannel channel;

    private WriteAheadLog(Path path, boolean fsync) {
        this.path = path;
        this.fsync = fsync;
    }

    /**
     * Reads every complete record, truncates a damaged tail, and opens the log for appending.
     */
    public static WriteAheadLog open(Path path, boolean fsync, List<Recovery> recoveryOut) throws IOException {
        WriteAheadLog log = new WriteAheadLog(path, fsync);
        recoveryOut.add(log.recover());
        log.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return log;
    }

    private Recovery recover() throws IOException {
        if (!Files.exists(path)) {
            return new Recovery(List.of(), 0);
        }
        byte[] data = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(data);
        List<List<LogOp>> records = new ArrayList<>();
        int validEnd = 0;
        while (buf.remaining() >= HEADER_BYTES) {
            int length = buf.getInt();
            long crc = buf.getLong();
            if (length < 0 || length > buf.remaining()) {
                break; // torn write: the payload never fully reached the disk
            }
            byte[] payload = new byte[length];
            buf.get(payload);
            if (checksum(payload) != crc) {
                break;
            }
            records.add(decode(payload));
            validEnd = buf.position();
        }
        long discarded = data.length - validEnd;
        if (discarded > 0) {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ch.truncate(validEnd);
                ch.force(true);
            }
        }
        return new Recovery(records, discarded);
    }

    /** Appends one statement's changes as a single record. */
    public void append(List<LogOp> ops) {
        try {
            byte[] payload = encode(ops);
            ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + payload.length);
            buf.putInt(payload.length).putLong(checksum(payload)).put(payload).flip();
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            if (fsync) {
                channel.force(false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to " + path, e);
        }
    }

    /**
     * Replaces the whole log with a compact snapshot. The snapshot is written to a temporary file
     * and then renamed over the log, so a crash leaves either the old log or the new one.
     */
    public void rewrite(List<LogOp> snapshot) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            channel.close();
            byte[] payload = encode(snapshot);
            ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + payload.length);
            buf.putInt(payload.length).putLong(checksum(payload)).put(payload).flip();
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rewrite " + path, e);
        }
    }

    public long sizeInBytes() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static long checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return crc.getValue();
    }

    // ---------------------------------------------------------------- encoding

    static byte[] encode(List<LogOp> ops) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(ops.size());
            for (LogOp op : ops) {
                switch (op) {
                    case LogOp.CreateTable c -> {
                        out.writeByte(CREATE_TABLE);
                        out.writeUTF(c.table());
                        out.writeInt(c.columns().size());
                        for (Column col : c.columns()) {
                            out.writeUTF(col.name());
                            out.writeUTF(col.type().name());
                            out.writeBoolean(col.primaryKey());
                            out.writeBoolean(col.notNull());
                        }
                    }
                    case LogOp.DropTable d -> {
                        out.writeByte(DROP_TABLE);
                        out.writeUTF(d.table());
                    }
                    case LogOp.CreateIndex c -> {
                        out.writeByte(CREATE_INDEX);
                        out.writeUTF(c.index());
                        out.writeUTF(c.table());
                        out.writeUTF(c.column());
                        out.writeBoolean(c.unique());
                    }
                    case LogOp.DropIndex d -> {
                        out.writeByte(DROP_INDEX);
                        out.writeUTF(d.index());
                        out.writeUTF(d.table());
                    }
                    case LogOp.Insert i -> {
                        out.writeByte(INSERT);
                        out.writeUTF(i.table());
                        out.writeLong(i.rowId());
                        writeValues(out, i.values());
                    }
                    case LogOp.Delete d -> {
                        out.writeByte(DELETE);
                        out.writeUTF(d.table());
                        out.writeLong(d.rowId());
                    }
                    case LogOp.Update u -> {
                        out.writeByte(UPDATE);
                        out.writeUTF(u.table());
                        out.writeLong(u.rowId());
                        writeValues(out, u.values());
                    }
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // cannot happen with an in-memory stream
        }
    }

    static List<LogOp> decode(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int count = in.readInt();
        List<LogOp> ops = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            byte type = in.readByte();
            ops.add(switch (type) {
                case CREATE_TABLE -> {
                    String table = in.readUTF();
                    int n = in.readInt();
                    List<Column> cols = new ArrayList<>(n);
                    for (int c = 0; c < n; c++) {
                        cols.add(new Column(in.readUTF(), DataType.valueOf(in.readUTF()), in.readBoolean(), in.readBoolean()));
                    }
                    yield new LogOp.CreateTable(table, cols);
                }
                case DROP_TABLE -> new LogOp.DropTable(in.readUTF());
                case CREATE_INDEX -> new LogOp.CreateIndex(in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean());
                case DROP_INDEX -> new LogOp.DropIndex(in.readUTF(), in.readUTF());
                case INSERT -> new LogOp.Insert(in.readUTF(), in.readLong(), readValues(in));
                case DELETE -> new LogOp.Delete(in.readUTF(), in.readLong());
                case UPDATE -> new LogOp.Update(in.readUTF(), in.readLong(), readValues(in));
                default -> throw new IOException("Unknown log record type " + type);
            });
        }
        return ops;
    }

    private static void writeValues(DataOutputStream out, Value[] values) throws IOException {
        out.writeInt(values.length);
        for (Value v : values) {
            switch (v) {
                case Value.NullValue n -> out.writeByte(TAG_NULL);
                case Value.IntValue i -> {
                    out.writeByte(TAG_INT);
                    out.writeLong(i.value());
                }
                case Value.TextValue t -> {
                    out.writeByte(TAG_TEXT);
                    out.writeUTF(t.value());
                }
            }
        }
    }

    private static Value[] readValues(DataInputStream in) throws IOException {
        Value[] values = new Value[in.readInt()];
        for (int i = 0; i < values.length; i++) {
            byte tag = in.readByte();
            values[i] = switch (tag) {
                case TAG_NULL -> Value.NULL;
                case TAG_INT -> Value.of(in.readLong());
                case TAG_TEXT -> Value.of(in.readUTF());
                default -> throw new IOException("Unknown value tag " + tag);
            };
        }
        return values;
    }
}
