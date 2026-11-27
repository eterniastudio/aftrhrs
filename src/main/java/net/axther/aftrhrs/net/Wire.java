package net.axther.aftrhrs.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.axther.aftrhrs.AftrHrs;
import net.axther.aftrhrs.core.Feather;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The byte format of the {@code aftrhrs:api} channel (docs/companion-protocol.md), unchanged from Feather: one flag
 * byte (0 = JSON, 1 = gzip JSON) and the body. Big server messages go out as {@code part} messages. Items are
 * {@code {"id":..,"n":..}} when plain, else {@code {"b": base64 of gzipped NBT}} (what Paper's
 * {@code ItemStack#serializeAsBytes()} wrote; the client reads both the same way).
 */
public final class Wire {
    public static final String CHANNEL = "aftrhrs:api";
    /** Bodies above this are gzipped. */
    private static final int GZIP_OVER = 2048;
    /** Bytes of payload per part, before base64 (about 512 KiB once encoded). */
    private static final int PART_BYTES = 384 * 1024;
    /** Largest message accepted from a client (they only send small requests). */
    static final int MAX_IN = 64 * 1024;
    /** Largest single payload the client accepts (a part is well under this). */
    static final int MAX_OUT = 4 * 1024 * 1024;

    private Wire() {
    }

    /** Encodes a message into one or more payloads (flag byte + body). */
    static List<byte[]> encode(JsonObject message, int partId) {
        return encode(message.toString(), partId);
    }

    /** {@link #encode(JsonObject, int)} for a message already turned into JSON text (sent as it is). */
    static List<byte[]> encode(String message, int partId) {
        byte[] json = message.getBytes(StandardCharsets.UTF_8);
        byte[] payload;
        if (json.length > GZIP_OVER) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(json.length / 4 + 16);
            out.write(1);
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(json);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            payload = out.toByteArray();
        } else {
            payload = new byte[json.length + 1];
            payload[0] = 0;
            System.arraycopy(json, 0, payload, 1, json.length);
        }
        return split(payload, partId);
    }

    /** One payload as it is, or (bigger than a part) as {@code part} messages. */
    private static List<byte[]> split(byte[] payload, int partId) {
        if (payload.length <= PART_BYTES) return List.of(payload);
        int n = (payload.length + PART_BYTES - 1) / PART_BYTES;
        List<byte[]> parts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] slice = Arrays.copyOfRange(payload, i * PART_BYTES, Math.min(payload.length, (i + 1) * PART_BYTES));
            JsonObject part = new JsonObject();
            part.addProperty("t", "part");
            part.addProperty("id", partId);
            part.addProperty("i", i);
            part.addProperty("n", n);
            part.addProperty("b", Base64.getEncoder().encodeToString(slice));
            byte[] body = part.toString().getBytes(StandardCharsets.UTF_8);
            byte[] framed = new byte[body.length + 1];
            System.arraycopy(body, 0, framed, 1, body.length);
            parts.add(framed);
        }
        return parts;
    }

    /**
     * Where {@link #encodeStreaming} writes a message: JSON text through {@link #writer()}; while a digest is set
     * ({@link #digest}) it is fed the UTF-8 bytes written.
     */
    public static final class Stream {
        private final Writer writer;
        private final Tee tee;

        private Stream(Writer writer, Tee tee) {
            this.writer = writer;
            this.tee = tee;
        }

        public Writer writer() {
            return writer;
        }

        /** Hashes what is written from now on into {@code md} (null: stop). Text written before is not hashed. */
        public void digest(MessageDigest md) throws IOException {
            writer.flush();
            tee.digest = md;
        }

        /** UTF-8 bytes written so far (before gzip). */
        public long bytes() throws IOException {
            writer.flush();
            return tee.count;
        }
    }

    /** Writes the JSON text of one message to a {@link Stream}. */
    @FunctionalInterface
    public interface StreamBody {
        void write(Stream out) throws IOException;
    }

    /** Counts (and, while set, hashes) the bytes on their way into gzip. */
    private static final class Tee extends FilterOutputStream {
        long count;
        MessageDigest digest;

        Tee(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
            if (digest != null) digest.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
            if (digest != null) digest.update(b, off, len);
        }
    }

    /**
     * Encodes a big message written as a stream (gzip always), split into parts like {@link #encode}: no JSON string
     * or full-size byte array of the text is ever made. Any thread.
     */
    static List<byte[]> encodeStreaming(int partId, StreamBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        out.write(1);
        GZIPOutputStream gz = new GZIPOutputStream(out, 32 * 1024);
        Tee tee = new Tee(gz);
        try (Writer w = new OutputStreamWriter(tee, StandardCharsets.UTF_8)) {
            body.write(new Stream(w, tee));
        }
        return split(out.toByteArray(), partId);
    }

    /** Reads a client payload, or null when it is malformed, too big, or a part (clients may not split). */
    static JsonObject decode(byte[] payload) {
        if (payload == null || payload.length < 3 || payload.length > MAX_IN) return null;
        try {
            byte[] body;
            if (payload[0] == 0) {
                body = Arrays.copyOfRange(payload, 1, payload.length);
            } else if (payload[0] == 1) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1))) {
                    body = in.readNBytes(MAX_IN + 1);
                }
                if (body.length > MAX_IN) return null;
            } else {
                return null;
            }
            JsonElement el = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("t") || !o.get("t").isJsonPrimitive() || "part".equals(o.get("t").getAsString())) return null;
            return o;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ values

    /** Adventure text as component JSON (what Feather sent). */
    public static JsonElement text(Component c) {
        return c == null ? null : GsonComponentSerializer.gson().serializeToTree(c);
    }

    /** Vanilla text as component JSON. */
    public static JsonElement text(net.minecraft.network.chat.Component c) {
        if (c == null) return null;
        HolderLookup.Provider reg = registries();
        var ops = reg == null ? JsonOps.INSTANCE : reg.createSerializationContext(JsonOps.INSTANCE);
        return ComponentSerialization.CODEC.encodeStart(ops, c).result().orElse(null);
    }

    /** An item with its amount; null for air. */
    public static JsonObject item(ItemStack it) {
        return item(it, it == null ? 0 : it.getCount());
    }

    public static JsonObject item(ItemStack it, int amount) {
        if (it == null || it.isEmpty() || amount <= 0) return null;
        JsonObject o = new JsonObject();
        String b64 = it.getComponentsPatch().isEmpty() ? null : bytes(it);
        if (b64 == null) o.addProperty("id", BuiltInRegistries.ITEM.getKey(it.getItem()).toString());
        else o.addProperty("b", b64);
        if (amount != 1) o.addProperty("n", amount);
        return o;
    }

    /** A plain item from its type (tag and item choices). */
    public static JsonObject item(Item item) {
        if (item == null || item == Items.AIR) return null;
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(item).toString());
        return o;
    }

    /** The item an id names, or null. */
    static Item itemType(String id) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        if (key == null) return null;
        return BuiltInRegistries.ITEM.getOptional(key).filter(i -> i != Items.AIR).orElse(null);
    }

    /** One item (count 1) as base64 of gzipped NBT with a DataVersion, like Paper's serializeAsBytes. */
    private static String bytes(ItemStack it) {
        HolderLookup.Provider reg = registries();
        if (reg == null) return null;
        try {
            Tag tag = ItemStack.CODEC.encodeStart(reg.createSerializationContext(NbtOps.INSTANCE), it.copyWithCount(1)).getOrThrow();
            if (!(tag instanceof CompoundTag compound)) return null;
            compound.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(compound, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException | RuntimeException e) {
            AftrHrs.LOG.debug("Item not encodable for the Companion", e);
            return null;
        }
    }

    private static HolderLookup.Provider registries() {
        var server = Feather.serverOrNull();
        return server == null ? null : server.registryAccess();
    }

    // ------------------------------------------------------------------ reading client fields safely

    public static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        String s = e.getAsString();
        return s.length() > 256 ? s.substring(0, 256) : s;
    }

    public static int num(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        try {
            double d = e.getAsDouble();
            if (Double.isNaN(d)) return fallback;
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }
}
