package net.axther.aftrhrs.feature.smithing;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * What a smith holds: with tongs in hand, the stations see the piece in the tongs (and put pieces into them); a bare
 * hand takes a hot piece too, but burns.
 */
public final class Hands {
    private Hands() {
    }

    public static boolean tongs(ItemStack s) {
        return s.getItem() == Smithing.TONGS;
    }

    /** The item the smith works with: the piece in the tongs, else the hand's stack. */
    public static ItemStack held(Player p, InteractionHand hand) {
        ItemStack s = p.getItemInHand(hand);
        if (tongs(s)) {
            ItemStack in = s.get(Smithing.HELD);
            return in == null ? ItemStack.EMPTY : in;
        }
        return s;
    }

    /** Hand is free to receive a piece: empty, or empty tongs. */
    public static boolean free(Player p, InteractionHand hand) {
        ItemStack s = p.getItemInHand(hand);
        return s.isEmpty() || tongs(s) && held(p, hand).isEmpty();
    }

    /** Takes {@code count} of the held piece (from the tongs or the hand) and returns it. */
    public static ItemStack take(Player p, InteractionHand hand, int count) {
        ItemStack s = p.getItemInHand(hand);
        if (tongs(s)) {
            ItemStack in = held(p, hand).copy();
            ItemStack out = in.split(count);
            if (in.isEmpty()) s.remove(Smithing.HELD);
            else s.set(Smithing.HELD, in);
            return out;
        }
        if (p.isCreative()) return s.copyWithCount(Math.min(count, s.getCount()));
        return s.split(count);
    }

    /** Replaces the held piece (in the tongs or the hand) with {@code with}. */
    public static void replace(Player p, InteractionHand hand, ItemStack with) {
        ItemStack s = p.getItemInHand(hand);
        if (tongs(s)) {
            if (with.isEmpty()) s.remove(Smithing.HELD);
            else s.set(Smithing.HELD, with);
        } else {
            p.setItemInHand(hand, with);
        }
    }

    /**
     * Gives a piece to the smith: into empty tongs in that hand, else the empty hand, else the inventory (or dropped).
     * A hot piece taken without tongs burns.
     */
    public static void give(Player p, InteractionHand hand, ItemStack piece) {
        if (piece.isEmpty()) return;
        Level level = p.level();
        ItemStack s = p.getItemInHand(hand);
        if (tongs(s) && held(p, hand).isEmpty()) {
            s.set(Smithing.HELD, piece);
            return;
        }
        if (Heat.of(piece, level) >= Heat.BURNS) Heat.burn(p);
        if (s.isEmpty()) p.setItemInHand(hand, piece);
        else if (!p.getInventory().add(piece)) p.drop(piece, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }
}
