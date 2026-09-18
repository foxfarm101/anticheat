/**
 * MiningSampler.java samples Spigot 1.8.8 mining state on the server thread.
 * The C++ detector interprets these values; no detection thresholds belong here.
 */

package dev.fox.anticheat.version;

import dev.fox.anticheat.event.DigEvent;
import dev.fox.anticheat.event.MiningContext;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.IBlockData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public final class MiningSampler{
    // Read a snapshot, or explain why this target cannot be sampled reliably.
    public MiningContext sample(Player player, DigEvent target){
        if(!Bukkit.isPrimaryThread())
            throw new IllegalStateException("World read off server thread");

        World world = player.getWorld();

        if(player.getGameMode() != GameMode.SURVIVAL || player.isDead())
            return unavailable(world, "not_live_survival");

        if(target.y < 0 || target.y >= world.getMaxHeight()
            || !world.isChunkLoaded(target.x >> 4, target.z >> 4)){
            return unavailable(world, "invalid_or_unloaded_target");
        }

        EntityPlayer handle = ((CraftPlayer) player).getHandle();
        double dx = handle.locX - (target.x + 0.5);
        double dy = handle.locY + 1.5 - (target.y + 0.5);
        double dz = handle.locZ - (target.z + 0.5);

        if(dx * dx + dy * dy + dz * dz > 36)
            return unavailable(world, "outside_sampling_range");

        org.bukkit.block.Block block = world.getBlockAt(target.x, target.y, target.z);

        if(block.getType() == Material.AIR || block.isLiquid())
            return unavailable(world, "air_or_liquid");

        BlockPosition position = new BlockPosition(target.x, target.y, target.z);
        IBlockData state = handle.world.getType(position);

        // Read the server's mining-strength value, not an anticheat verdict.
        double damage = state.getBlock().getDamage(handle, handle.world, position);
        ItemStack hand = player.getItemInHand();
        String tool = hand == null ? "AIR" : hand.getType().name();
        String material = block.getType().name();
        String key = material + ":" + block.getData() + "|" + tool + "|"
            + player.getInventory().getHeldItemSlot() + "|" + enchantments(hand) + "|" + effects(player)
            + "|ground=" + handle.onGround;

        return new MiningContext(
            world.getUID().toString(),
            key,
            material,
            tool,
            "",
            damage,
            true
        );
    }

    // Sort state-key components so equivalent snapshots have identical text.
    private Map<String, Integer> enchantments(ItemStack hand){
        Map<String, Integer> values = new TreeMap<>();

        if(hand != null){
            for(Map.Entry<Enchantment, Integer> entry : hand.getEnchantments().entrySet()){
                values.put(entry.getKey().getName(), entry.getValue());
            }
        }

        return values;
    }

    private Map<String, Integer> effects(Player player){
        Map<String, Integer> values = new TreeMap<>();

        for(PotionEffect effect : player.getActivePotionEffects()){
            values.put(effect.getType().getName(), effect.getAmplifier());
        }

        return values;
    }

    // Missing context stays unavailable rather than being replaced with assumptions.
    private MiningContext unavailable(World world, String reason){
        return new MiningContext(
            world.getUID().toString(),
            "",
            "UNKNOWN",
            "UNKNOWN",
            reason,
            0,
            false
        );
    }
}
