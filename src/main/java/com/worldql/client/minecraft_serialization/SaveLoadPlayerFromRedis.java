package com.worldql.client.minecraft_serialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worldql.client.WorldQLClient;
import net.minecraft.nbt.MojangsonParser;
import net.minecraft.nbt.NBTTagCompound;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_18_R1.entity.CraftEntity;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SaveLoadPlayerFromRedis {
    public static void savePlayerToRedis(Player player) {
        if (player.getHealth() == 0) {
            return;
        }

        if (WorldQLClient.getPluginInstance().playerDataSavingManager.skip(player, 1000)) {
            return;
        }

        Jedis j = WorldQLClient.pool.getResource();
        HashMap<String, Object> oldPlayerData = new HashMap<String, Object>();
        Boolean hasOldData = false;

        if (j.exists("player-" + player.getUniqueId())) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                oldPlayerData = mapper.readValue(j.get("player-" + player.getUniqueId()), new TypeReference<Map<String, Object>>() {
                });
                if (WorldQLClient.getPluginInstance().inventorySyncOnly) {
                    hasOldData = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        HashMap<String, Object> playerData = new HashMap<String, Object>();
        String[] inventoryStrings = PlayerDataSerialize.playerInventoryToBase64(player.getInventory());
        playerData.put("inventory", inventoryStrings[0]);
        playerData.put("armor", inventoryStrings[1]);
        playerData.put("heldslot", player.getInventory().getHeldItemSlot());
        playerData.put("hunger", player.getFoodLevel());
        playerData.put("health", player.getHealth());
        playerData.put("xp", ExperienceUtil.getTotalExperience(player));
        playerData.put("pitch", player.getLocation().getPitch());
        playerData.put("yaw", player.getLocation().getYaw());
        if (hasOldData) {
            playerData.put("x", oldPlayerData.get("x"));
            playerData.put("y", oldPlayerData.get("y"));
            playerData.put("z", oldPlayerData.get("z"));
        } else {
            playerData.put("x", player.getLocation().getX());
            playerData.put("y", player.getLocation().getY());
            playerData.put("z", player.getLocation().getZ());
        }
        playerData.put("world", player.getWorld().getName());
        playerData.put("isGliding", player.isGliding());

        // get speed for better elytra flight
        Vector velocity = player.getVelocity();
        String velocityString = velocity.getX() + "," + velocity.getY() + "," + velocity.getZ();
        playerData.put("velocity", velocityString);

        Collection<PotionEffect> potionEffects = player.getActivePotionEffects();
        Iterator<PotionEffect> iterator = potionEffects.iterator();
        StringBuilder potionString = new StringBuilder();
        while (iterator.hasNext()) {
            PotionEffect effect = iterator.next();
            potionString.append(effect.getType().getName()).append(",");
            potionString.append(((Integer) effect.getDuration())).append(",");
            potionString.append(((Integer) effect.getAmplifier())).append(",");
        }
        playerData.put("potions", potionString.toString());


        // is the player riding a horse
        if (player.isInsideVehicle() && player.getVehicle() instanceof Horse) {
            Horse horse = (Horse) player.getVehicle();
            playerData.put("horse", getNBT(horse));
            horse.getInventory().setArmor(null);
            horse.getInventory().setSaddle(null);
            horse.remove();
        }
        // check for boat
        if (player.isInsideVehicle() && player.getVehicle() instanceof Boat) {
            Boat boat = (Boat) player.getVehicle();
            playerData.put("boat", getNBT(boat));
            boat.remove();
        }
        // check for nether strider
        if (player.isInsideVehicle() && player.getVehicle() instanceof Strider) {
            Strider strider = (Strider) player.getVehicle();
            playerData.put("strider", getNBT(strider));
            strider.remove();
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            String playerAsJson = mapper.writeValueAsString(playerData);
            j.set("player-" + player.getUniqueId(), playerAsJson);
        } catch (Exception e) {
            e.printStackTrace();
        }
        WorldQLClient.pool.returnResource(j);
        WorldQLClient.getPluginInstance().playerDataSavingManager.markSave(player);
    }

    private static String getNBT(Entity e) {
        net.minecraft.world.entity.Entity nms = ((CraftEntity) e).getHandle();
        NBTTagCompound nbt = new NBTTagCompound();
        nms.e(nbt);
        return nbt.toString();
    }

    private static void setNBT(Entity e, String value) {
        net.minecraft.world.entity.Entity nms = ((CraftEntity) e).getHandle();
        try {
            NBTTagCompound nbtv = MojangsonParser.a(value);
            nms.g(nbtv);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void setPlayerData(String playerJSON, Player player) throws IOException {
        HashMap<String, Object> playerData = new HashMap<String, Object>();
        ObjectMapper mapper = new ObjectMapper();
        playerData = mapper.readValue(playerJSON, new TypeReference<Map<String, Object>>() {
        });

        // teleport the player to the right place.
        World w = player.getServer().getWorld((String) playerData.get("world"));
        if (!WorldQLClient.getPluginInstance().inventorySyncOnly && playerData.get("x") != null) {
            Location loc = new Location(w, (Double) playerData.get("x"), (Double) playerData.get("y"), (Double) playerData.get("z"),
                    (float) (double) (Double) playerData.get("yaw"), (float) (double) (Double) playerData.get("pitch"));
            player.teleport(loc);
        }

        if (WorldQLClient.getPluginInstance().syncPlayerInventory) {
            ItemStack[] playerInventory = PlayerDataSerialize.itemStackArrayFromBase64((String) playerData.get("inventory"));
            ItemStack[] armorContents = PlayerDataSerialize.itemStackArrayFromBase64((String) playerData.get("armor"));
            player.getInventory().setContents(playerInventory);
            player.getInventory().setArmorContents(armorContents);
        }

        ExperienceUtil.setTotalExperience(player, (Integer) playerData.get("xp"));
        player.setFoodLevel((Integer) playerData.get("hunger"));
        player.setHealth((Double) playerData.get("health"));
        player.getInventory().setHeldItemSlot((Integer) playerData.get("heldslot"));

        // set velocity
        String[] velocityComponents = ((String) playerData.get("velocity")).split(",");
        Vector velocity = new Vector(Double.parseDouble(velocityComponents[0]), Double.parseDouble(velocityComponents[1]),
                Double.parseDouble(velocityComponents[2]));
        player.setVelocity(velocity);

        if (!WorldQLClient.getPluginInstance().inventorySyncOnly) {
            if (playerData.containsKey("horse")) {
                Entity newHorse = player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
                setNBT(newHorse, (String) playerData.get("horse"));
                newHorse.addPassenger(player);
            }
            if (playerData.containsKey("boat")) {
                Entity newBoat = player.getWorld().spawnEntity(player.getLocation(), EntityType.BOAT);
                setNBT(newBoat, (String) playerData.get("boat"));
                newBoat.addPassenger(player);
            }
            if (playerData.containsKey("strider")) {
                Entity newStrider = player.getWorld().spawnEntity(player.getLocation(), EntityType.STRIDER);
                setNBT(newStrider, (String) playerData.get("strider"));
                newStrider.addPassenger(player);
            }
        }

        player.setGliding((boolean) playerData.get("isGliding"));

        String potions[] = ((String) playerData.get("potions")).split(",");
        // remove all potion effects
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        if (potions.length > 1) {
            // loop through effects and apply them.
            int c = 0;
            while (c < potions.length) {
                PotionEffectType effectType = PotionEffectType.getByName(potions[c]);
                c++;
                int duration = Integer.parseInt(potions[c]);
                c++;
                int amplifier = Integer.parseInt(potions[c]);
                PotionEffect potionEffect = effectType.createEffect(duration, amplifier);
                player.addPotionEffect(potionEffect);
                c++;
            }
        }
    }
}