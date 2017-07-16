package uk.kihira.beproductive;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.mojang.authlib.GameProfile;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.server.FMLServerHandler;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;

@Mod(modid = "BeProductive", acceptableRemoteVersions = "*")
public class BeProductive extends CommandBase {

    private static final Logger logger = LogManager.getLogger("BeProductive");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Configuration config;
    private File userDataFile;

    //Current data
    /**
     * Current time on count in tick per player
     */
    private Multiset<UUID> timeOnCount = HashMultiset.create();
    /**
     * Unix time when player can rejoin
     */
    private HashMap<UUID, Long> rejoinTime = new HashMap<UUID, Long>();

    //Per Player Settings
    /**
     * Max time on per player in ticks. Overrides {@link #maxTimeOnGlobal}
     */
    private HashMap<UUID, Integer> maxTimeOn = new HashMap<UUID, Integer>();
    /**
     * Time that the player has earned to be online.
     * Not currently used
     */
    private HashMap<UUID, Integer> earnedTimeOn = new HashMap<UUID, Integer>();
    /**
     * How long the player has to wait until they can rejoin
     */
    private HashMap<UUID, Integer> breakTime = new HashMap<UUID, Integer>();

    //Global Settings
    /**
     * Max time on for all players in ticks. Overriden by {@link #maxTimeOn}
     */
    private int maxTimeOnGlobal = 0;
    /**
     * Time earned every minute the player is offline.
     * Not currently used
     */
    private float timeEarnedRatio = 1;
    /**
     * How long players have to wait until rejoining in milliseconds. Overriden by {@link #breakTime}
     */
    private long breakTimeGlobal = 0;
    private String kickMessage = "Go be productive!";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new Configuration(event.getSuggestedConfigurationFile());
        userDataFile = new File(event.getModConfigurationDirectory(), "beproductivedata.json");
        if (!userDataFile.exists()) {
            try {
                userDataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        loadSettings();
        saveSettings();

        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            update();
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.END && mc.theWorld != null && !mc.isSingleplayer()) {
            UUID uuid = mc.thePlayer.getUniqueID();
            timeOnCount.add(uuid);

            if ((maxTimeOn.containsKey(uuid) && timeOnCount.count(uuid) > maxTimeOn.get(uuid)) || (maxTimeOnGlobal != 0 && timeOnCount.count(uuid) > maxTimeOnGlobal)) {
                rejoinTime.put(uuid, System.currentTimeMillis() + (breakTime.containsKey(uuid) ? breakTime.get(uuid) * 50 : breakTimeGlobal * 50));
                timeOnCount.remove(uuid, timeOnCount.count(uuid));
            }

            //Disconnect if still on timeout
            if (rejoinTime.containsKey(uuid) && System.currentTimeMillis() < rejoinTime.get(uuid)) {
                mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
                mc.displayGuiScreen(new GuiDisconnected(null, String.format(kickMessage + " You can rejoin in approx. %s minute(s)",
                        (int) Math.floor(((rejoinTime.get(uuid) - System.currentTimeMillis()) / 1000F) / 60F)), new TextComponentString("")));
            }
        }
    }

    private void update() {
        ArrayList<UUID> onlinePlayers = new ArrayList<UUID>();
        for (Object obj : FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayerList()) {
            EntityPlayerMP player = (EntityPlayerMP) obj;
            UUID uuid = player.getUniqueID();

            onlinePlayers.add(uuid);
            timeOnCount.add(uuid);

            //Kick players who are on too long
            if ((maxTimeOn.containsKey(uuid) && timeOnCount.count(uuid) > maxTimeOn.get(uuid)) || (maxTimeOnGlobal != 0 && timeOnCount.count(uuid) > maxTimeOnGlobal)) {
                rejoinTime.put(uuid, System.currentTimeMillis() + (breakTime.containsKey(uuid) ? breakTime.get(uuid) * 50 : breakTimeGlobal * 50));
                kickPlayerForTime(player);
                timeOnCount.remove(uuid, timeOnCount.count(uuid));
            }
        }

        //Decrease timeOnCount time for players that aren't online
        HashMultiset<UUID> uuids = HashMultiset.create();
        for (UUID entry : timeOnCount.elementSet()) {
            if (!onlinePlayers.contains(entry)) {
                uuids.add(entry);
            }
        }
        Multisets.removeOccurrences(timeOnCount, uuids);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        UUID uuid = event.player.getUniqueID();
        if (rejoinTime.containsKey(uuid)) {
            //Check if they can rejoin, if so remove them from the map
            if (System.currentTimeMillis() < rejoinTime.get(uuid)) {
                kickPlayerForTime((EntityPlayerMP) event.player);
            }
            else {
                rejoinTime.remove(uuid);
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        saveSettings();
    }

    private void kickPlayerForTime(EntityPlayerMP player) {
        player.connection.kickPlayerFromServer(String.format(kickMessage + " You can rejoin in approx. %s minute(s)",
                (int) Math.floor(((rejoinTime.get(player.getUniqueID()) - System.currentTimeMillis()) / 1000F) / 60F)));
    }

    //A lot of this command code is not very nice but it works.
    @Override
    public String getCommandName() {
        return "beproductive";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return null;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 1) {
            if (args[0].equals("set")) {
                if (args[1].equals("breaktime")) {
                    if (args.length != 4) {
                        throw new WrongUsageException("Usage: /beproductive set breaktime <playername> <minutes>");
                    }
                    GameProfile profile = getGameProfileForPlayer(args[2]);
                    int ticks = getTicksFromMinutes(args[3]);

                    if (ticks == 0) breakTime.remove(profile.getId());
                    else breakTime.put(profile.getId(), ticks);
                    notifyCommandListener(sender, this, "Set break time for %s to %s minute(s)", profile.getName(), args[3]);
                }
                else if (args[1].equals("maxtimeon")) {
                    if (args.length != 4) {
                        throw new WrongUsageException("Usage: /beproductive set maxtimeon <playername> <minutes>");
                    }
                    GameProfile profile = getGameProfileForPlayer(args[2]);
                    int ticks = getTicksFromMinutes(args[3]);

                    if (ticks == 0) maxTimeOn.remove(profile.getId());
                    else maxTimeOn.put(profile.getId(), ticks);
                    notifyCommandListener(sender, this, "Set maximum time on for %s to %s minute(s)", profile.getName(), args[3]);
                }
                else if (args[1].equals("globalmaxtimeon")) {
                    if (args.length != 3) {
                        throw new WrongUsageException("Usage: /beproductive set globalmaxtimeon <minutes>");
                    }

                    maxTimeOnGlobal = getTicksFromMinutes(args[2]);
                    notifyCommandListener(sender, this, "Set maximum time on for all to %s minute(s)", args[2]);
                }
                else if (args[1].equals("globalbreaktime")) {
                    if (args.length != 3) {
                        throw new WrongUsageException("Usage: /beproductive set globalbreaktime <minutes>");
                    }

                    breakTimeGlobal = Integer.valueOf(args[2]) * 60000;
                    notifyCommandListener(sender, this, "Set break time for all to %s minute(s)", args[2]);
                }
                else {
                    throw new WrongUsageException("Usage: /beproductive set <globalbreaktime|globalmaxtimeon|maxtimeon|breaktime>");
                }
            }
            else if (args[0].equals("timeout")) {
                String playerName = args[1];
                GameProfile profile = getGameProfileForPlayer(playerName);
                long milliseconds = args.length == 3 ? Integer.valueOf(args[2]) * 60000 : breakTime.containsKey(profile.getId()) ? breakTime.get(profile.getId()) * 50 : breakTimeGlobal * 50;

                timeOnCount.remove(profile.getId(), timeOnCount.count(profile.getId()));
                rejoinTime.put(profile.getId(), System.currentTimeMillis() + milliseconds);
                kickPlayerForTime(FMLServerHandler.instance().getServer().getPlayerList().getPlayerByUsername(playerName));
                notifyCommandListener(sender, this, "Timed out %s for %s minute(s)", profile.getName(), milliseconds / 60000);
            }
            else if (args[0].equals("reset")) {
                String playerName = args[1];
                GameProfile profile = getGameProfileForPlayer(playerName);

                rejoinTime.remove(profile.getId());
                timeOnCount.remove(profile.getId(), timeOnCount.count(profile.getId()));
                notifyCommandListener(sender, this, "Reset times for %s", profile.getName());
            }
            else {
                throw new WrongUsageException("Usage: /beproductive <set|reset|timeout|reloadconfig>");
            }
        }
        else if (args.length == 1 && args[0].equals("reloadconfig")) {
            loadSettings();
            saveSettings();

            notifyCommandListener(sender, this, "Reloaded config from file");
        }
        else {
            throw new WrongUsageException("Usage: /beproductive <set|reset|timeout|reloadconfig>");
        }
        saveSettings();
    }

    @Override
    public List getTabCompletionOptions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "set", "timeout", "reset", "reloadconfig");
        }
        else if (args.length == 2) {
            if (args[0].equals("set")) {
                return getListOfStringsMatchingLastWord(args, "breaktime", "maxtimeon", "globalmaxtimeon", "globalbreaktime");
            }
            else if (args[0].equals("timeout") || args[0].equals("reset")) {
                return getListOfStringsMatchingLastWord(args, FMLServerHandler.instance().getServer().getAllUsernames());
            }
        }
        else if (args.length == 3) {
            if (args[1].equals("breaktime") || args[1].equals("maxtimeon")) {
                return getListOfStringsMatchingLastWord(args, FMLServerHandler.instance().getServer().getAllUsernames());
            }
        }
        return super.getTabCompletionOptions(server, sender, args, pos);
    }

    private GameProfile getGameProfileForPlayer(String playerName) throws CommandException {
        GameProfile profile = FMLServerHandler.instance().getServer().getPlayerList().getPlayerByUsername(playerName).getGameProfile();
        if (profile == null) {
            throw new CommandException("Unable to find the profile for the player %s", playerName);
        }
        return profile;
    }

    private int getTicksFromMinutes(String string) throws CommandException {
        try {
            return Integer.valueOf(string) * 60 * 20;
        } catch (NumberFormatException e) {
            throw new NumberInvalidException("Invalid number for minutes");
        }
    }

    private void loadSettings() {
        maxTimeOnGlobal = config.getInt("maxTimeOnGlobal", Configuration.CATEGORY_GENERAL, 0, 0, Integer.MAX_VALUE,
                "Global time in minutes that the player can play until timed out") * 60 * 20;
        breakTimeGlobal = config.getInt("breakTimeGlobal", Configuration.CATEGORY_GENERAL, 0, 0, Integer.MAX_VALUE,
                "Global time in minutes that the player cannot join the server for after being timed out") * 60000;
        kickMessage = config.getString("kickMessage", Configuration.CATEGORY_GENERAL, "Go be productive!",
                "Kick message to be displayed when user needs to take a break or times to join");

        JsonReader reader = null;
        try {
            reader = new JsonReader(new FileReader(userDataFile));

            reader.beginArray();
            maxTimeOn = gson.fromJson(reader, new TypeToken<HashMap<UUID, Integer>>() {{}}.getType());
            breakTime = gson.fromJson(reader, new TypeToken<HashMap<UUID, Integer>>() {{}}.getType());
            rejoinTime = gson.fromJson(reader, new TypeToken<HashMap<UUID, Long>>() {{}}.getType());
            reader.endArray();

            reader.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(reader);
        }

        if (maxTimeOn == null) {
            maxTimeOn = new HashMap<UUID, Integer>();
        }
        if (breakTime == null) {
            breakTime = new HashMap<UUID, Integer>();
        }
        if (rejoinTime == null) {
            rejoinTime = new HashMap<UUID, Long>();
        }
    }

    private void saveSettings() {
        config.get(Configuration.CATEGORY_GENERAL, "maxTimeOnGlobal", 0).set((maxTimeOnGlobal / 20) / 60);
        config.get(Configuration.CATEGORY_GENERAL, "breakTimeGlobal", 0).set(breakTimeGlobal / 60000);
        config.get(Configuration.CATEGORY_GENERAL, "kickMessage", "Go be productive!").set(kickMessage);

        JsonWriter writer = null;
        try {
            writer = new JsonWriter(new FileWriter(userDataFile));

            writer.beginArray();
            gson.toJson(maxTimeOn, new TypeToken<HashMap<UUID, Integer>>() {{}}.getType(), writer);
            gson.toJson(breakTime, new TypeToken<HashMap<UUID, Integer>>() {{}}.getType(), writer);
            gson.toJson(rejoinTime, new TypeToken<HashMap<UUID, Long>>() {{}}.getType(), writer);
            writer.endArray();

            writer.close();

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(writer);
        }

        if (config.hasChanged()) {
            config.save();
        }
    }
}
