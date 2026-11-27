package net.axther.aftrhrs.client.places;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.axther.aftrhrs.client.net.Link;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Your places as Feather knows them ({@code places} feature): homes, warps, spawn, your last death point and your
 * graves. Going there is always the normal command ({@code /home}, {@code /warp}, {@code /spawn}, {@code /back}), with
 * Feather's own warm-ups, costs and cooldowns.
 */
public final class PlaceState {
	/** One place. {@code kind}: home, warp, spawn, death, grave. {@code icon}: its own icon (an item id), or "". */
	public record Place(String kind, String name, String world, double x, double y, double z, String icon) {
		/** The place's own icon as an item, or EMPTY (then its symbol stands for it). */
		public net.minecraft.world.item.ItemStack iconStack() {
			if (icon.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
			net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(icon);
			if (id == null) return net.minecraft.world.item.ItemStack.EMPTY;
			return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id)
				.map(net.minecraft.world.item.ItemStack::new).orElse(net.minecraft.world.item.ItemStack.EMPTY);
		}

		public int color() {
			return switch (kind) {
				case "home" -> 0x86E8B0;
				case "warp" -> 0x8FD3FF;
				case "spawn" -> 0xFFE7A8;
				case "death" -> 0xFF8F8F;
				case "grave" -> 0xC3A6FF;
				default -> 0xF1EDFF;
			};
		}

		public String symbol() {
			return switch (kind) {
				case "home" -> "⌂";
				case "warp" -> "✦";
				case "spawn" -> "☀";
				case "death" -> "☠";
				case "grave" -> "✝";
				default -> "•";
			};
		}

		/** The command that takes you there, or null (graves: walk). */
		public String command() {
			return switch (kind) {
				case "home" -> "home " + name;
				case "warp" -> "warp " + name;
				case "spawn" -> "spawn";
				case "death" -> "back";
				default -> null;
			};
		}

		/** Beams that are always shown (you probably want to get back there). */
		public boolean urgent() {
			return kind.equals("death") || kind.equals("grave");
		}
	}

	public static final PlaceState INSTANCE = new PlaceState();

	public List<Place> places = List.of();
	public String world = "";
	/** Beams for every place (the Places screen's toggle); otherwise only death points and graves. */

	private PlaceState() {
	}

	public static void register() {
		Link.on("places", INSTANCE::accept);
	}

	public boolean active() {
		return Link.has("places");
	}

	public void clear() {
		places = List.of();
		world = "";
	}

	private void accept(JsonObject m) {
		world = Link.str(m, "w");
		List<Place> list = new ArrayList<>();
		if (m.has("list") && m.get("list").isJsonArray()) {
			for (JsonElement e : m.getAsJsonArray("list")) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				list.add(new Place(Link.str(o, "kind"), Link.str(o, "name"), Link.str(o, "w"), Link.dbl(o, "x", 0), Link.dbl(o, "y", 0), Link.dbl(o, "z", 0), Link.str(o, "icon")));
			}
		}
		places = List.copyOf(list);
	}

	/** Places in the player's world (Feather resends the list when the player changes worlds); a new list the caller may change. */
	public List<Place> here() {
		return new ArrayList<>(hereView());
	}

	/** The {@link #places} and {@link #world} that {@link #hereList} was made from. */
	private List<Place> hereSrc;
	private String hereWorld;
	private List<Place> hereList = List.of();

	/** As {@link #here()}, but the same unmodifiable list every frame until the places or the world change (the compass). */
	public List<Place> hereView() {
		if (places != hereSrc || !world.equals(hereWorld)) {
			List<Place> out = new ArrayList<>();
			for (Place p : places) if (p.world.equals(world)) out.add(p);
			hereList = List.copyOf(out);
			hereSrc = places;
			hereWorld = world;
		}
		return hereList;
	}

	public static double distance(Player player, Place p) {
		double dx = p.x - player.getX(), dz = p.z - player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}
}
