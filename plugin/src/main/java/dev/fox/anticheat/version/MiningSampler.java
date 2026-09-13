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

/** Version-specific collection. No timing comparisons or FastBreak thresholds. */
public final class MiningSampler{
    public MiningContext sample(Player player, DigEvent target){
        if(!Bukkit.isPrimaryThread()){ throw new IllegalStateException("World read off server thread"); }
        World world = player.getWorld();
        if(player.getGameMode() != GameMode.SURVIVAL || player.isDead()){
            return unavailable(world, "not_live_survival");
        }
        if(target.y < 0 || target.y >= world.getMaxHeight()
            || !world.isChunkLoaded(target.x >> 4, target.z >> 4)){
            return unavailable(world, "invalid_or_unloaded_target");
        }
        EntityPlayer handle = ((CraftPlayer) player).getHandle();
        double dx = handle.locX - (target.x + 0.5);
        double dy = handle.locY + 1.5 - (target.y + 0.5);
        double dz = handle.locZ - (target.z + 0.5);
        if(dx * dx + dy * dy + dz * dz > 36){ return unavailable(world, "outside_sampling_range"); }
        org.bukkit.block.Block block = world.getBlockAt(target.x, target.y, target.z);
        if(block.getType() == Material.AIR || block.isLiquid()){
            return unavailable(world, "air_or_liquid");
        }
        BlockPosition position = new BlockPosition(target.x, target.y, target.z);
        IBlockData state = handle.world.getType(position);
        // Read the pinned server's mining-strength primitive as an observation.
        // The C++ check owns interpretation of this value.
        double damage = state.getBlock().getDamage(handle, handle.world, position);
        ItemStack hand = player.getItemInHand();
        String tool = hand == null ? "AIR" : hand.getType().name();
        Map<String, Integer> enchants = new TreeMap<>();
        if(hand != null){
            for(Map.Entry<Enchantment, Integer> entry : hand.getEnchantments().entrySet()){
                enchants.put(entry.getKey().getName(), entry.getValue());
            }
        }
        Map<String, Integer> effects = new TreeMap<>();
        for(PotionEffect effect : player.getActivePotionEffects()){
            effects.put(effect.getType().getName(), effect.getAmplifier());
        }
        String material = block.getType().name();
        String key = material + ":" + block.getData() + "|" + tool + "|"
            + player.getInventory().getHeldItemSlot() + "|" + enchants + "|" + effects
            + "|ground=" + handle.onGround;
        return new MiningContext(world.getUID().toString(), key, material, tool, "", damage, true);
    }
    private MiningContext unavailable(World world, String reason){
        return new MiningContext(world.getUID().toString(), "", "UNKNOWN", "UNKNOWN", reason, 0, false);
    }
}
