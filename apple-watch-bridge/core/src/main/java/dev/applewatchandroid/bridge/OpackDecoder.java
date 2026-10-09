package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal bounded decoder for Apple's OPACK serialization (used by
 * Sharing/Rapport alloy services, e.g. com.apple.private.alloy.sharing.paireddevice).
 *
 * <p>Read-only and defensive: input ≤ 256 KiB, nesting ≤ 12, container items
 * ≤ 1024, strings ≤ 64 KiB. Object-reference (UID) opcodes are supported with
 * cycle protection. No object graphs are instantiated by name — everything
 * lands in plain Map/List/String/Long/Boolean/byte[].</p>
 */
final class OpackDecoder {

    static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_ITEMS = 1024;
    private static final int MAX_STRING = 64 * 1024;

    private OpackDecoder() {
    }

    static Object decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("OPACK payload is absent");
        }
        if (payload.length > MAX_BYTES) {
            throw new IllegalArgumentException("OPACK payload is too large");
        }
        Parser parser = new Parser(payload);
        Object value = parser.value(0, false);
        if (parser.remaining() != 0) {
            throw new IllegalArgumentException("OPACK trailing bytes");
        }
        return value;
    }

    /** Compact one-line rendering: bytes as bytes(n), depth-bounded. */
    static String summarize(Object value) {
        StringBuilder out = new StringBuilder(512);
        render(value, out, 0);
        return out.toString();
    }

    private static void render(Object value, StringBuilder out, int depth) {
        if (out.length() > 1900) {
            return;
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Long number) {
            out.append(number);
        } else if (value instanceof Double number) {
            out.append(number);
        } else if (value instanceof String text) {
            out.append('"');
            out.append(text.length() <= 160 ? text : text.substring(0, 160) + "…");
            out.append('"');
        } else if (value instanceof byte[] bytes) {
            out.append("bytes(").append(bytes.length).append(')');
        } else if (value instanceof List<?> list) {
            if (depth >= 4) {
                out.append("[…]").append("(n=").append(list.size()).append(')');
                return;
            }
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                if (i >= 24) {
                    out.append("…+").append(list.size() - 24);
                    break;
                }
                render(list.get(i), out, depth + 1);
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            if (depth >= 4) {
                out.append("{…}").append("(n=").append(map.size()).append(')');
                return;
            }
            out.append('{');
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (i > 0) out.append(',');
                if (i >= 32) {
                    out.append("…+").append(map.size() - 32);
                    break;
                }
                render(entry.getKey(), out, depth + 1);
                out.append('=');
                render(entry.getValue(), out, depth + 1);
                i++;
            }
            out.append('}');
        } else {
            out.append("<?>").append(value.getClass().getSimpleName());
        }
    }

    private static final class Parser {
        private final ByteBuffer in;
        private final List<Object> objectList = new ArrayList<>();

        Parser(byte[] payload) {
            this.in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        int remaining() {
            return in.remaining();
        }

        Object value(int depth, boolean nested) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("OPACK nesting too deep");
            }
            if (!in.hasRemaining()) {
                throw new IllegalArgumentException("OPACK truncated");
            }
            int opcode = in.get() & 0xFF;
            Object result;
            boolean track = true;
            if (opcode == 0x01) {
                result = Boolean.TRUE;
                track = false;
            } else if (opcode == 0x02) {
                result = Boolean.FALSE;
                track = false;
            } else if (opcode == 0x04) {
                result = null;
                track = false;
            } else if (opcode == 0x05) {
                need(16);
                byte[] raw = new byte[16];
                in.get(raw);
                result = uuid(raw);
            } else if (opcode == 0x06) {
                need(8);
                // Native NSDate encodes seconds since 2001-01-01 as IEEE754.
                result = finite(in.getDouble());
            } else if (opcode == 0x07) {
                result = -1L;
                track = false;
            } else if (opcode >= 0x08 && opcode <= 0x2F) {
                result = (long) (opcode - 0x08);
                track = false;
            } else if (opcode == 0x35) {
                need(4);
                result = finite(in.getFloat());
            } else if (opcode == 0x36) {
                need(8);
                result = finite(in.getDouble());
            } else if ((opcode & 0xF0) == 0x30) {
                int size = 1 << (opcode & 0x0F);
                if (size > 8) {
                    throw new IllegalArgumentException("OPACK int size invalid");
                }
                need(size);
                long number = 0;
                for (int i = 0; i < size; i++) {
                    number |= ((long) (in.get() & 0xFF)) << (8 * i);
                }
                result = number;
            } else if (opcode >= 0x40 && opcode <= 0x60) {
                result = string(opcode - 0x40);
                track = opcode != 0x40;
            } else if (opcode > 0x60 && opcode <= 0x64) {
                int lenBytes = 1 << ((opcode & 0x0F) - 1);
                need(lenBytes);
                long length = 0;
                for (int i = 0; i < lenBytes; i++) {
                    length |= ((long) (in.get() & 0xFF)) << (8 * i);
                }
                if (length < 0 || length > MAX_STRING) {
                    throw new IllegalArgumentException("OPACK string too large");
                }
                result = string((int) length);
            } else if (opcode >= 0x70 && opcode <= 0x90) {
                result = bytes(opcode - 0x70);
                track = opcode != 0x70;
            } else if (opcode >= 0x91 && opcode <= 0x94) {
                int lenBytes = 1 << ((opcode & 0x0F) - 1);
                need(lenBytes);
                long length = 0;
                for (int i = 0; i < lenBytes; i++) {
                    length |= ((long) (in.get() & 0xFF)) << (8 * i);
                }
                if (length < 0 || length > MAX_BYTES) {
                    throw new IllegalArgumentException("OPACK data too large");
                }
                result = bytes((int) length);
            } else if ((opcode & 0xF0) == 0xD0) {
                result = list(opcode & 0x0F, depth);
                track = false;
            } else if ((opcode & 0xF0) == 0xE0) {
                result = map(opcode & 0x0F, depth);
                track = false;
            } else if (opcode >= 0xA0 && opcode <= 0xC0) {
                result = reference(opcode - 0xA0);
                track = false;
            } else if (opcode >= 0xC1 && opcode <= 0xC4) {
                int lenBytes = 1 << (opcode - 0xC1);
                need(lenBytes);
                long uid = 0;
                for (int i = 0; i < lenBytes; i++) {
                    uid |= ((long) (in.get() & 0xFF)) << (8 * i);
                }
                result = reference(uid);
                track = false;
            } else if (opcode == 0x03) {
                throw new IllegalArgumentException("OPACK stray terminator");
            } else {
                throw new IllegalArgumentException(
                        "OPACK unknown opcode 0x" + Integer.toHexString(opcode));
            }
            // The native decoder appends every eligible inline value. Equal
            // values at different positions still consume distinct indices.
            if (track && result != null) {
                if (objectList.size() >= 4096) {
                    throw new IllegalArgumentException("OPACK object table too large");
                }
                objectList.add(result);
            }
            return result;
        }

        private Object reference(long uid) {
            if (uid < 0 || uid >= objectList.size()) {
                throw new IllegalArgumentException("OPACK bad object reference");
            }
            return objectList.get((int) uid);
        }

        private List<Object> list(int count, int depth) {
            List<Object> out = new ArrayList<>();
            if (count == 0x0F) {
                while (true) {
                    if (!in.hasRemaining()) {
                        throw new IllegalArgumentException("OPACK unterminated list");
                    }
                    if ((in.get(in.position()) & 0xFF) == 0x03) {
                        in.get();
                        break;
                    }
                    if (out.size() >= MAX_ITEMS) {
                        throw new IllegalArgumentException("OPACK list too large");
                    }
                    out.add(value(depth + 1, true));
                }
            } else {
                if (count > MAX_ITEMS) {
                    throw new IllegalArgumentException("OPACK list too large");
                }
                for (int i = 0; i < count; i++) {
                    out.add(value(depth + 1, true));
                }
            }
            return out;
        }

        private Map<String, Object> map(int count, int depth) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (count == 0x0F) {
                while (true) {
                    if (!in.hasRemaining()) {
                        throw new IllegalArgumentException("OPACK unterminated map");
                    }
                    if ((in.get(in.position()) & 0xFF) == 0x03) {
                        in.get();
                        break;
                    }
                    if (out.size() >= MAX_ITEMS) {
                        throw new IllegalArgumentException("OPACK map too large");
                    }
                    Object key = value(depth + 1, true);
                    Object val = value(depth + 1, true);
                    put(out, key, val);
                }
            } else {
                if (count > MAX_ITEMS) {
                    throw new IllegalArgumentException("OPACK map too large");
                }
                for (int i = 0; i < count; i++) {
                    Object key = value(depth + 1, true);
                    Object val = value(depth + 1, true);
                    put(out, key, val);
                }
            }
            return out;
        }

        private void put(Map<String, Object> out, Object key, Object value) {
            if (!(key instanceof String text) || out.containsKey(text)) {
                throw new IllegalArgumentException("OPACK invalid or duplicate dictionary key");
            }
            out.put(text, value);
        }

        private double finite(double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("OPACK non-finite number");
            }
            return number;
        }

        private String string(int length) {
            if (length < 0 || length > MAX_STRING) {
                throw new IllegalArgumentException("OPACK string length invalid");
            }
            need(length);
            byte[] raw = new byte[length];
            in.get(raw);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("OPACK invalid UTF-8", invalid);
            }
        }

        private byte[] bytes(int length) {
            if (length < 0 || length > MAX_BYTES) {
                throw new IllegalArgumentException("OPACK data length invalid");
            }
            need(length);
            byte[] raw = new byte[length];
            in.get(raw);
            return raw;
        }

        private void need(int count) {
            if (in.remaining() < count) {
                throw new IllegalArgumentException("OPACK truncated");
            }
        }

        private static String uuid(byte[] raw) {
            StringBuilder out = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) {
                    out.append('-');
                }
                out.append(Character.forDigit((raw[i] >> 4) & 0xF, 16));
                out.append(Character.forDigit(raw[i] & 0xF, 16));
            }
            return out.toString();
        }
    }
}
