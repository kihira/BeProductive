package uk.kihira.beproductive;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Mod(modid = "BeProductive")
public class BeProductive extends CommandBase {

    private static final Logger logger = LogManager.getLogger("BeProductive");
    private Configuration config;

    //Current data
    /**
     * Current time on count in tick per player
     */
    private Multiset<UUID> timeOnCount = HashMultiset.create();
    /**
     * Time left in ticks until player can rejoin the server
     */
    private Multiset<UUID> timeToRejoin = HashMultiset.create();

    //Per Player Settings
    /**
     * Max time on per player in ticks. Overrides {@link #maxTimeOnGlobal}
     */
    private HashMap<UUID, Integer> maxTimeOn = new HashMap<UUID, Integer>();
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
     * How long players have to wait until rejoining. Overriden by {@link #breakTime}
     */
    private int breakTimeGlobal = 0;
    private String kickMessage = "Go be productive! You can rejoin in %s";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new Configuration(event.getSuggestedConfigurationFile());
        loadSettings();

        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(this);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            for (Object obj : FMLCommonHandler.instance().getMinecraftServerInstance().getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) obj;
                UUID uuid = player.getUniqueID();
                timeOnCount.add(uuid);
                logger.info("Increased time for " + player.getCommandSenderName() + " to " + timeOnCount.count(uuid));

                //Kick players who are on too long
                if ((maxTimeOn.containsKey(uuid) && timeOnCount.count(uuid) > maxTimeOn.get(uuid)) || (maxTimeOnGlobal != 0 && timeOnCount.count(uuid) > maxTimeOnGlobal)) {
                    timeToRejoin.setCount(uuid, breakTime.containsKey(uuid) ? breakTime.get(uuid) : breakTimeGlobal);
                    kickPlayerForTime(player);
                    timeOnCount.remove(uuid, timeOnCount.count(uuid));
                }
            }

            //TODO Decrease timeOnCount time for players that aren't online?

            //Decrease breakTime for players
            for (UUID entry : timeToRejoin.elementSet()) {
                timeToRejoin.remove(entry);
            }
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (timeToRejoin.contains(event.player.getUniqueID())) {
            kickPlayerForTime((EntityPlayerMP) event.player);
        }
    }

    private void kickPlayerForTime(EntityPlayerMP player) {
        player.playerNetServerHandler.kickPlayerFromServer(String.format(kickMessage, "INSERT TIME HERE"));
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
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (args[0].equals("set")) {
                if (args[1].equals("breaktime")) {
                    if (args.length != 4) {
                        throw new CommandException("Usage: /beproductive set breaktime <playername> <minutes>");
                    }
                    GameProfile profile = getGameProfileForPlayer(args[2]);
                    int ticks = getTicksFromMinutes(args[3]);

                    breakTime.put(profile.getId(), ticks);
                    func_152373_a(sender, this, "Set break time for %s to %s minute(s)", profile.getName(), args[3]);
                }
                else if (args[1].equals("maxtimeon")) {
                    if (args.length != 4) {
                        throw new CommandException("Usage: /beproductive set maxtimeon <playername> <minutes>");
                    }
                    GameProfile profile = getGameProfileForPlayer(args[2]);
                    int ticks = getTicksFromMinutes(args[3]);

                    maxTimeOn.put(profile.getId(), ticks);
                    func_152373_a(sender, this, "Set maximum time on for %s to %s minute(s)", profile.getName(), args[3]);
                }
                else if (args[1].equals("globalmaxtimeon")) {
                    if (args.length != 3) {
                        throw new CommandException("Usage: /beproductive set globalmaxtimeon <minutes>");
                    }

                    maxTimeOnGlobal = getTicksFromMinutes(args[2]);
                    func_152373_a(sender, this, "Set maximum time on for all to %s minute(s)", args[2]);
                }
                else if (args[1].equals("globalbreaktime")) {
                    if (args.length != 3) {
                        throw new CommandException("Usage: /beproductive set globalbreaktime <minutes>");
                    }

                    breakTimeGlobal = getTicksFromMinutes(args[2]);
                    func_152373_a(sender, this, "Set break time for all to %s minute(s)", args[2]);
                }
                saveSettings();
            }
            if (args[0].equals("timeout")) {
                String playerName = args[1];
                GameProfile profile = getGameProfileForPlayer(playerName);
                int ticks = breakTime.containsKey(profile.getId()) ? breakTime.get(profile.getId()) : breakTimeGlobal;

                //If optional timeout time specified otherwise we use default
                if (args.length == 3) {
                    ticks = getTicksFromMinutes(args[2]);
                }
                timeToRejoin.setCount(profile.getId(), ticks);
                kickPlayerForTime(MinecraftServer.getServer().getConfigurationManager().func_152612_a(playerName));
                func_152373_a(sender, this, "Timed out %s for %s minute(s)", profile.getName(), (ticks / 20) / 60);
            }
        }
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "set", "timeout");
        }
        else if (args.length == 2) {
            if (args[0].equals("set")) {
                return getListOfStringsMatchingLastWord(args, "breaktime", "maxtimeon", "globalmaxtimeon", "globalbreaktime");
            }
            else if (args[0].equals("timeout")) {
                return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
            }
        }
        else if (args.length == 3) {
            if (args[1].equals("breaktime") || args[1].equals("maxtimeon")) {
                return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
            }
        }
        return super.addTabCompletionOptions(sender, args);
    }

    private GameProfile getGameProfileForPlayer(String playerName) throws CommandException {
        GameProfile profile = MinecraftServer.getServer().func_152358_ax().func_152655_a(playerName);
        if (profile == null) {
            throw new CommandException("Unable to find the profile for the player %s", playerName);
        }
        return profile;
    }

    private int getTicksFromMinutes(String string) throws CommandException {
        try {
            return Integer.valueOf(string) * 60 * 20;
        } catch (NumberFormatException e) {
            throw new CommandException("Invalid number for minutes");
        }
    }

    private void loadSettings() {
        maxTimeOnGlobal = config.getInt("maxTimeOnGlobal", Configuration.CATEGORY_GENERAL, 60, 0, Integer.MAX_VALUE,
                "Global time in minutes that the player can play until timed out");
        breakTimeGlobal = config.getInt("breakTimeGlobal", Configuration.CATEGORY_GENERAL, 60, 0, Integer.MAX_VALUE,
                "Global time in minutes that the player cannot join the server for after being timed out");
    }

    private void saveSettings() {
        if (config.hasChanged()) {
            config.save();
        }
    }
}
