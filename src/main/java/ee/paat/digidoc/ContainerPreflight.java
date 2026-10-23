package ee.paat.digidoc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Checks the immutable snapshot before DSS can expand it. No declared ZIP size grants a budget. */
final class ContainerPreflight {
    private static final int MAX_NAME_BYTES = 1024;
    private static final int MAX_DEPTH = 2;
    private static final int MAX_ENTRIES = 10_000;
    private ContainerPreflight() {}

    static void check(byte[] bytes, long maxBytes) throws IOException {
        check(bytes, new Budget(maxBytes), 0, true, false);
    }

    private static void check(byte[] bytes, Budget budget, int depth, boolean topLevel,
                              boolean legacyParsing) throws IOException {
        if (isZip(bytes)) {
            if (depth > MAX_DEPTH) throw unsupported();
            zip(bytes, budget, depth);
        } else if (topLevel) {
            xml(bytes, 0, budget, depth, true, true);
        } else {
            // DDOC's SignatureInputStream discards arbitrary bytes before the first '<'.
            // Mirror that input view: a filename or an XML magic-byte sniff is insufficient.
            for (int start = 0; start < bytes.length; start++) {
                if (bytes[start] == '<') {
                    xml(bytes, start, budget, depth, false, legacyParsing);
                    break;
                }
            }
        }
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && (u32(bytes, 0) == 0x04034b50L || u32(bytes, 0) == 0x06054b50L);
    }

    private static void zip(byte[] bytes, Budget budget, int depth) throws IOException {
        int end = -1;
        for (int i = Math.max(0, bytes.length - 65_557); i <= bytes.length - 22; i++) {
            if (u32(bytes, i) == 0x06054b50L && (long) i + 22 + u16(bytes, i + 20) == bytes.length) {
                if (end != -1) throw unsupported();
                end = i;
            }
        }
        if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0
                || u16(bytes, end + 8) != u16(bytes, end + 10)) throw unsupported();
        int count = u16(bytes, end + 10);
        budget.entries(count);
        long centralSize = u32(bytes, end + 12);
        long centralOffset = u32(bytes, end + 16);
        // ZIP64 and split archives cannot fit this classic layout; fail closed instead of guessing offsets.
        if (count == 0xffff || centralOffset + centralSize != end) throw unsupported();
        int at = index(centralOffset, bytes.length);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            require(bytes, at, 46);
            if (u32(bytes, at) != 0x02014b50L) throw unsupported();
            int flags = u16(bytes, at + 8);
            int method = u16(bytes, at + 10);
            int nameLength = u16(bytes, at + 28);
            int extraLength = u16(bytes, at + 30);
            int commentLength = u16(bytes, at + 32);
            int length = 46 + nameLength + extraLength + commentLength;
            require(bytes, at, length);
            if (at + length > end || u16(bytes, at + 34) != 0
                    || (flags & ~0x080e) != 0 || (method != 0 && method != 8)) throw unsupported();
            byte[] name = Arrays.copyOfRange(bytes, at + 46, at + 46 + nameLength);
            checkNameLength(new String(name, StandardCharsets.UTF_8));
            entries.add(new Entry(name, flags, method, u32(bytes, at + 16),
                    u32(bytes, at + 20), u32(bytes, at + 24), index(u32(bytes, at + 42), bytes.length)));
            at += length;
        }
        if (at != end) throw unsupported();
        entries.sort(Comparator.comparingInt(Entry::offset));
        List<byte[]> contents = new ArrayList<>(count);
        boolean asics = false;
        int nextLocal = 0;
        for (Entry entry : entries) {
            // A complete contiguous local chain excludes prepended/trailing/concatenated archives,
            // hidden local entries, reused offsets and a second EOCD outside entry payloads.
            if (entry.offset != nextLocal) throw unsupported();
            require(bytes, nextLocal, 30);
            if (u32(bytes, nextLocal) != 0x04034b50L || u16(bytes, nextLocal + 6) != entry.flags
                    || u16(bytes, nextLocal + 8) != entry.method) throw unsupported();
            int nameLength = u16(bytes, nextLocal + 26);
            int extraLength = u16(bytes, nextLocal + 28);
            require(bytes, nextLocal + 30, nameLength + extraLength);
            checkNameLength(new String(bytes, nextLocal + 30, nameLength, StandardCharsets.UTF_8));
            if (!Arrays.equals(entry.name, Arrays.copyOfRange(bytes, nextLocal + 30, nextLocal + 30 + nameLength))) {
                throw unsupported();
            }
            if ((entry.flags & 8) == 0 && (u32(bytes, nextLocal + 14) != entry.crc
                    || u32(bytes, nextLocal + 18) != entry.compressed || u32(bytes, nextLocal + 22) != entry.size)) {
                throw unsupported();
            }
            int data = nextLocal + 30 + nameLength + extraLength;
            int afterData = index((long) data + entry.compressed, bytes.length);
            if (afterData > centralOffset) throw unsupported();
            byte[] expanded = inflate(bytes, data, entry, budget);
            contents.add(expanded);
            // Recognize the MIME value independently of filename aliases and entry ordering.
            if (Arrays.equals(expanded, "application/vnd.etsi.asic-s+zip".getBytes(StandardCharsets.US_ASCII))) {
                asics = true;
            }
            nextLocal = afterData;
            if ((entry.flags & 8) != 0) {
                require(bytes, nextLocal, 12);
                // A signed descriptor is 16 bytes; unsigned is 12 (CRC may itself equal the signature).
                boolean signed = u32(bytes, nextLocal) == 0x08074b50L
                        && descriptorMatches(bytes, nextLocal + 4, entry);
                if (signed) nextLocal += 4;
                if (!descriptorMatches(bytes, nextLocal, entry)) throw unsupported();
                nextLocal += 12;
            }
        }
        if (nextLocal != centralOffset) throw unsupported();
        // Reject every EOCD signature in the comment, even a truncated or invalid-length record.
        // EOCDs inside nested data entries belong to those containers and are checked recursively.
        for (int i = end + 22; i <= bytes.length - 4; i++) {
            if (u32(bytes, i) == 0x06054b50L) {
                throw unsupported();
            }
        }
        // Timestamped ASiC-S probes every data payload with the legacy DDOC reader, which
        // can process declarations before discovering that the XML is not a SignedDoc.
        for (byte[] content : contents) check(content, budget, depth + 1, false, asics);
    }

    private static boolean descriptorMatches(byte[] bytes, int at, Entry entry) {
        return at >= 0 && at <= bytes.length - 12 && u32(bytes, at) == entry.crc
                && u32(bytes, at + 4) == entry.compressed && u32(bytes, at + 8) == entry.size;
    }

    private static byte[] inflate(byte[] bytes, int at, Entry entry, Budget budget) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        int compressed = index(entry.compressed, bytes.length);
        if (entry.method == 0) {
            budget.bytes(compressed);
            output.write(bytes, at, compressed);
            crc.update(bytes, at, compressed);
        } else {
            Inflater inflater = new Inflater(true);
            try {
                inflater.setInput(bytes, at, compressed);
                byte[] buffer = new byte[8192];
                while (!inflater.finished()) {
                    int read = inflater.inflate(buffer);
                    if (read == 0 && !inflater.finished()) throw unsupported();
                    budget.bytes(read);
                    output.write(buffer, 0, read);
                    crc.update(buffer, 0, read);
                }
                if (inflater.getBytesRead() != compressed) throw unsupported();
            } catch (DataFormatException e) {
                throw unsupported();
            } finally { inflater.end(); }
        }
        if (output.size() != entry.size || crc.getValue() != entry.crc) throw unsupported();
        return output.toByteArray();
    }

    /** Also protects nested DDOC from the legacy reader's filesystem fallback. */
    private static void xml(byte[] bytes, int start, Budget budget, int depth, boolean required,
                            boolean legacyParsing) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        boolean inDataFile = false;
        boolean hasBody = false;
        boolean root = true;
        boolean unsafeDeclaration = false;
        String type = null;
        long declaredSize = 0;
        StringBuilder body = new StringBuilder();
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes, start, bytes.length - start));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        unsafeDeclaration = true;
                        if (required || legacyParsing || !root) throw unsupported();
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (root) {
                            root = false;
                            if (!required && !"SignedDoc".equals(reader.getLocalName())) return;
                            if (unsafeDeclaration) throw unsupported();
                            if (depth > MAX_DEPTH) throw unsupported();
                        }
                        if ("DataFile".equals(reader.getLocalName())) {
                            type = reader.getAttributeValue(null, "ContentType");
                            checkNameLength(reader.getAttributeValue(null, "Filename"));
                            try { PathPolicy.fileName(reader.getAttributeValue(null, "Filename")); }
                            catch (ToolException e) { throw unsupported(); }
                            if (inDataFile || !("EMBEDDED_BASE64".equals(type) || "EMBEDDED".equals(type))) throw unsupported();
                            String size = reader.getAttributeValue("", "Size");
                            declaredSize = 0;
                            if (size != null) {
                                if (!size.matches("[0-9]+")) throw unsupported();
                                try { declaredSize = Long.parseLong(size); }
                                catch (NumberFormatException e) { throw tooLarge(); }
                            }
                            // The legacy reader allocates from Size before reading the body.
                            budget.bytes(declaredSize);
                            inDataFile = true;
                            hasBody = false;
                            body.setLength(0);
                            budget.entries(1);
                        }
                    } else if (inDataFile && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                        if (!reader.isWhiteSpace()) hasBody = true;
                        body.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT && "DataFile".equals(reader.getLocalName())) {
                        if (!hasBody) throw unsupported();
                        byte[] payload;
                        try {
                            payload = "EMBEDDED_BASE64".equals(type) ? Base64.getMimeDecoder().decode(body.toString())
                                    : body.toString().getBytes(StandardCharsets.UTF_8);
                        } catch (IllegalArgumentException e) { throw unsupported(); }
                        budget.bytes(Math.max(0L, payload.length - declaredSize));
                        check(payload, budget, depth + 1, false, false);
                        inDataFile = false;
                    }
                }
            } finally { reader.close(); }
        } catch (XMLStreamException e) {
            if (required) throw new ToolException("CONTAINER_ERROR", "The container could not be processed.");
            // Ordinary XML-like data is not a DDOC; a malformed SignedDoc is rejected by the builder.
            if (!root) throw unsupported();
        }
    }

    private record Entry(byte[] name, int flags, int method, long crc, long compressed, long size, int offset) {}
    private static final class Budget {
        private long remaining;
        private int entries;
        private Budget(long remaining) { this.remaining = remaining; }
        private void bytes(long count) {
            if (count > remaining) throw tooLarge();
            remaining -= count;
        }
        private void entries(int count) {
            if (count > MAX_ENTRIES - entries) throw tooLarge();
            entries += count;
        }
    }
    private static int index(long value, int length) {
        if (value < 0 || value > length) throw unsupported();
        return (int) value;
    }
    private static void require(byte[] bytes, int at, int length) {
        if (at < 0 || length < 0 || at > bytes.length - length) throw unsupported();
    }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8; }
    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8 | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }
    private static void checkNameLength(String name) {
        if (name != null && name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            throw new ToolException("NAME_LIMIT", "Container entry names must not exceed 1024 UTF-8 bytes.");
        }
    }
    private static ToolException unsupported() {
        return new ToolException("UNSUPPORTED_CONTAINER", "Unsupported or ambiguous container structure.");
    }
    private static ToolException tooLarge() {
        return new ToolException("SIZE_LIMIT", "Container or extracted data exceeds the configured byte limit.");
    }
}
