package net.axther.aftrhrs.client.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.axther.aftrhrs.client.Companion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Items and text as Feather sends them (docs/companion-protocol.md, "Items"). The forms without a registries argument
 * (and {@link #CACHE}) are for the client thread only; the ones taking registries (and a cache of the caller's) touch no
 * Minecraft state and run on any thread (the server recipe list is built on a background thread).
 */
public final class Decode {
	/**
	 * Decoded component-carrying items, by their base64 (recipe lists repeat the same items a lot). The least recently
	 * used go once there are {@link #CACHE_MAX} (storage terminals show ever new ones: worn tools, filled shulker boxes).
	 * Client thread only.
	 */
	private static final int CACHE_MAX = 4096;
	private static final Map<String, ItemStack> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
			return size() > CACHE_MAX;
		}
	};

	private Decode() {
	}

	public static void clearCache() {
		CACHE.clear();
	}

	/** The registries of the server being played (null when there is none yet). Client thread only. */
	public static HolderLookup.Provider currentRegistries() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) return mc.getConnection().registryAccess();
		return mc.level != null ? mc.level.registryAccess() : null;
	}

	/** An item with its count ({@code n}); {@link ItemStack#EMPTY} when unreadable. Client thread only. */
	public static ItemStack item(JsonElement el) {
		return item(el, currentRegistries(), CACHE);
	}

	/**
	 * {@link #item(JsonElement)} with the given registries (null: component-carrying items read as EMPTY) and decoded
	 * items remembered in {@code cache}. Any thread, as long as {@code cache} is only used by that thread.
	 */
	public static ItemStack item(JsonElement el, HolderLookup.Provider reg, Map<String, ItemStack> cache) {
		if (el == null || !el.isJsonObject()) return ItemStack.EMPTY;
		JsonObject o = el.getAsJsonObject();
		int n = Link.num(o, "n", 1);
		ItemStack base;
		if (o.has("id")) {
			Identifier id = Identifier.tryParse(Link.str(o, "id"));
			Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
			base = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
		} else if (o.has("b")) {
			String b64 = Link.str(o, "b");
			base = cache.get(b64);
			if (base == null) {
				base = bytes(b64, reg);
				// An unreadable item isn't remembered: it may read fine once the registries are there.
				if (!base.isEmpty()) cache.put(b64, base);
			}
		} else {
			return ItemStack.EMPTY;
		}
		if (base.isEmpty()) return ItemStack.EMPTY;
		ItemStack out = base.copy();
		out.setCount(Math.max(1, n));
		return out;
	}

	private static ItemStack bytes(String b64, HolderLookup.Provider reg) {
		if (reg == null) return ItemStack.EMPTY;
		try {
			CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(b64)), NbtAccounter.uncompressedQuota());
			tag.remove("DataVersion");
			return ItemStack.CODEC.parse(reg.createSerializationContext(NbtOps.INSTANCE), tag)
				.resultOrPartial(err -> Companion.LOG.debug("Item from Feather: {}", err))
				.orElse(ItemStack.EMPTY);
		} catch (Exception e) {
			Companion.LOG.debug("Unreadable item from Feather", e);
			return ItemStack.EMPTY;
		}
	}

	/** Component JSON; {@code fallback} when missing or unreadable. Client thread only. */
	public static Component text(JsonElement el, Component fallback) {
		return text(el, fallback, currentRegistries());
	}

	/** {@link #text(JsonElement, Component)} with the given registries (null: plain JSON ops). Any thread. */
	public static Component text(JsonElement el, Component fallback, HolderLookup.Provider reg) {
		if (el == null || el.isJsonNull()) return fallback;
		try {
			var ops = reg == null ? JsonOps.INSTANCE : reg.createSerializationContext(JsonOps.INSTANCE);
			return ComponentSerialization.CODEC.parse(ops, el).result().orElse(fallback);
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static Component text(JsonElement el) {
		return text(el, Component.empty());
	}
}
