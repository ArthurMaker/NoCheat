package cc.co.evenprime.bukkit.nocheat;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import cc.co.evenprime.bukkit.nocheat.actions.CustomAction;
import cc.co.evenprime.bukkit.nocheat.checks.AirbuildCheck;
import cc.co.evenprime.bukkit.nocheat.checks.BogusitemsCheck;
import cc.co.evenprime.bukkit.nocheat.checks.Check;
import cc.co.evenprime.bukkit.nocheat.checks.InfinitedurabilityCheck;
import cc.co.evenprime.bukkit.nocheat.checks.NukeCheck;
import cc.co.evenprime.bukkit.nocheat.checks.MovingCheck;
import cc.co.evenprime.bukkit.nocheat.checks.SpeedhackCheck;
import cc.co.evenprime.bukkit.nocheat.config.NoCheatConfiguration;
import cc.co.evenprime.bukkit.nocheat.data.PermissionData;

import com.ensifera.animosity.craftirc.CraftIRC;
import com.nijikokun.bukkit.Permissions.Permissions;
import com.nijiko.permissions.PermissionHandler;
import org.bukkit.plugin.Plugin;

/**
 * 
 * NoCheat
 * 
 * Check various player events for their plausibility and log/deny them based on
 * configuration
 * 
 * @author Evenprime
 */
public class NoCheat extends JavaPlugin {

    private MovingCheck          movingCheck;
    private SpeedhackCheck       speedhackCheck;
    private AirbuildCheck        airbuildCheck;
    private BogusitemsCheck      bogusitemsCheck;

    private Check[]              checks;

    private NoCheatConfiguration config;

    private boolean              useNewPermissionSystem    = false;

    private long                 exceptionWithPermissions  = 0;

    private int                  cleanUpTaskId             = -1;
    private int                  serverLagMeasureTaskSetup = -1;

    private int                  serverTicks               = 0;
    private long                 serverLagInMilliSeconds   = 0;
    private long                 lastServerTime            = 0;

    // Permissions, if available
    private PermissionHandler    permissions;

    // CraftIRC, if available
    private CraftIRC             irc;

    private Level                chatLevel;
    private Level                ircLevel;
    private Level                consoleLevel;
    private String               ircTag;
    private NukeCheck            nukeCheck;
    private InfinitedurabilityCheck infiniteCheck;

    private DataManager          dataManager;

    private CustomCommandSender  sender;
    private boolean              showStartupMessages       = true;


    public NoCheat() {

    }

    public String[] getMessagesOfTheDay() {

        return new String[] {"This version of NoCheat was written for CraftBukkit RB #1060", "NoCheat supports the new \"SuperPerms\" system. You can activate it in the config file."};
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {

        if(args.length == 0) {
            sender.sendMessage("NC: Using " + ((permissions == null) ? "isOp()" : "Permissions") + ". Activated checks/bugfixes: " + getActiveChecksAsString() + ". Total time used for moving check so far: " + (movingCheck.statisticElapsedTimeNano / 1000000L + " ms. Average time per move event: " + (movingCheck.statisticElapsedTimeNano / 1000L) / movingCheck.statisticTotalEvents + " us"));
            return true;
        } else if(args.length == 1 && args[0] != null && args[0].trim().equals("-p")) {
            if(sender instanceof Player) {
                Player p = (Player) sender;

                sender.sendMessage("NC: You have permissions: " + getPermissionsForPlayerAsString(p));
                return true;
            } else {
                sender.sendMessage("NC: You have to be a player to use this command");
                return true;
            }
        } else if(args.length == 2 && args[0] != null && args[0].trim().equals("-p")) {
            Player p = getServer().getPlayer(args[1]);

            if(p != null) {
                sender.sendMessage("NC: " + p.getName() + " has permissions: " + getPermissionsForPlayerAsString(p));
                return true;
            } else {
                sender.sendMessage("NC: Player " + args[1] + " was not found.");
                return true;
            }
        }

        return false;
    }

    public void onDisable() {

        PluginDescriptionFile pdfFile = this.getDescription();

        if(config != null)
            config.cleanup();

        try {
            dataManager.cancelPlayerDataTasks();

            teardownCleanupTask();
            teardownServerLagMeasureTask();
        } catch(Exception e) { /* Can't do much in case of error here... */
        }
        Logger.getLogger("Minecraft").info("[NoCheat] version [" + pdfFile.getVersion() + "] is disabled.");
    }

    public void onEnable() {

        dataManager = new DataManager();

        sender = new CustomCommandSender(getServer());
        // parse the nocheat.yml config file
        setupConfig();
        

        for(String s : getMessagesOfTheDay()) {
            if(showStartupMessages)
                Logger.getLogger("Minecraft").info("(NoCheat) Did you know? " + s);
        }

        movingCheck = new MovingCheck(this, config);
        speedhackCheck = new SpeedhackCheck(this, config);
        airbuildCheck = new AirbuildCheck(this, config);
        bogusitemsCheck = new BogusitemsCheck(this, config);
        nukeCheck = new NukeCheck(this, config);
        infiniteCheck = new InfinitedurabilityCheck(this, config);

        // just for convenience
        checks = new Check[] {movingCheck, speedhackCheck, airbuildCheck, bogusitemsCheck, nukeCheck, infiniteCheck};

        if(!this.getServer().getAllowFlight() && movingCheck.isActive()) {
            Logger.getLogger("Minecraft").warning("[NoCheat] you have set \"allow-flight=false\" in your server.properties file. That builtin anti-flying-mechanism will likely conflict with this plugin. Please consider deactivating it by setting it to \"true\"");
        }


        PluginDescriptionFile pdfFile = this.getDescription();

        // Get, if available, the Permissions and irc plugin
        if(!useNewPermissionSystem) {
            setupPermissions();
        }
        else {
            Logger.getLogger("Minecraft").info("[NoCheat] is using SuperPerms system.");
        }
        
        if(!ircLevel.equals(Level.OFF)) {
            setupIRC();
        }

        Logger.getLogger("Minecraft").info("[NoCheat] version [" + pdfFile.getVersion() + "] is enabled with the following checks: " + getActiveChecksAsString());

        setupCleanupTask();

        setupServerLagMeasureTask();
    }

    public DataManager getDataManager() {

        return dataManager;
    }

    private void setupCleanupTask() {

        if(cleanUpTaskId != -1) {
            return;
        }
        try {
            cleanUpTaskId = Bukkit.getServer().getScheduler().scheduleAsyncRepeatingTask(this, new Runnable() {

                @Override
                public void run() {

                    try {
                        if(getDataManager() != null) {
                            getDataManager().cleanPlayerDataCollection();
                        }
                    } catch(Exception e) {}
                }
            }, 5000, 5000);
        } catch(Exception e) {
            // It's not THAT important, so if it fails for whatever reason, just
            // let it be.
        }
    }

    private void teardownCleanupTask() {

        if(cleanUpTaskId != -1) {
            Bukkit.getServer().getScheduler().cancelTask(cleanUpTaskId);
        }
    }

    private void setupServerLagMeasureTask() {

        if(serverLagMeasureTaskSetup != -1) {
            return;
        }

        serverLagMeasureTaskSetup = Bukkit.getServer().getScheduler().scheduleAsyncRepeatingTask(this, new Runnable() {

            @Override
            public void run() {

                serverTicks += 10;
                long time = System.currentTimeMillis();
                serverLagInMilliSeconds = Math.abs((time - lastServerTime - 500) * 2);
                lastServerTime = time;

            }
        }, 10, 10);
    }

    private void teardownServerLagMeasureTask() {

        if(serverLagMeasureTaskSetup != -1) {
            Bukkit.getServer().getScheduler().cancelTask(serverLagMeasureTaskSetup);
        }
    }

    /**
     * Get, if available, a reference to the Permissions-plugin
     */
    private void setupPermissions() {

        Plugin permissionsPlugin = this.getServer().getPluginManager().getPlugin("Permissions");

        if(this.permissions == null) {
            if(permissionsPlugin != null) {
                this.permissions = ((Permissions) permissionsPlugin).getHandler();
            } else {
                PluginDescriptionFile pdfFile = this.getDescription();
                Logger.getLogger("Minecraft").warning("[NoCheat] version [" + pdfFile.getVersion() + "] couldn't find \"Permissions\" plugin. Falling back to SuperPerms system.");
                useNewPermissionSystem = true;
            }
        }
    }

    /**
     * Get, if available, a reference to the Permissions-plugin
     */
    private void setupIRC() {

        CraftIRC p = null;

        Plugin test = this.getServer().getPluginManager().getPlugin("CraftIRC");

        if(test != null && test instanceof CraftIRC) {
            p = (CraftIRC) test;
        }

        if(p == null) {
            PluginDescriptionFile pdfFile = this.getDescription();
            Logger.getLogger("Minecraft").info("[NoCheat] version [" + pdfFile.getVersion() + "] couldn't find CrafTIRC plugin. Disabling logging to IRC.");
        }

        irc = p;
    }

    /**
     * Log a violation message to all locations declared in the config file
     * 
     * @param message
     */
    public void log(Level l, String message) {

        if(l != null && message != null) {
            message = "NC: " + message;
            config.logger.log(l, message);
            logToConsole(l, message);
            logToChat(l, message);
            logToIRC(l, message);

        }
    }

    private void logToChat(Level l, String message) {

        if(chatLevel.intValue() <= l.intValue()) {
            for(Player player : getServer().getOnlinePlayers()) {
                if(hasPermission(player, PermissionData.PERMISSION_NOTIFY, false)) {
                    player.sendMessage("[" + l.getName() + "] " + message);
                }
            }
        }
    }

    private void logToIRC(Level l, String message) {

        if(irc != null && ircLevel.intValue() <= l.intValue()) {
            irc.sendMessageToTag("[" + l.getName() + "] " + message, ircTag);
        }
    }

    private void logToConsole(Level l, String message) {

        if(consoleLevel.intValue() <= l.intValue()) {
            Logger.getLogger("Minecraft").log(l, message);
        }
    }

    public boolean hasPermission(Player player, int permission, boolean ignoreOPstatus) {

        if(player == null)
            return false;

        if(useNewPermissionSystem) {
            return player.hasPermission(PermissionData.permissionNames[permission]);
        }

        try {
            if(permissions == null) {
                if(ignoreOPstatus) {
                    // OPs don't get special treatment
                    return false;
                } else {
                    return player.isOp();
                }
            } else {
                PermissionData data = dataManager.getPermissionData(player);
                long time = System.currentTimeMillis();
                if(data.lastUpdate[permission] + 10000 < time) {
                    data.lastUpdate[permission] = time;
                    data.cache[permission] = permissions.has(player, PermissionData.permissionNames[permission]);
                }
                return data.cache[permission];
            }
        } catch(Throwable e) {
            if(this.exceptionWithPermissions + 60000 < System.currentTimeMillis()) {
                // Prevent spam and recursion by definitely doing this only once
                this.exceptionWithPermissions = System.currentTimeMillis();

                String logtext = "Asking Permissions-Plugin if " + player.getName() + " has permission " + PermissionData.permissionNames[permission] + " caused an Exception " + e.getMessage() + ". Please review your permissions config file. This message is displayed at most once every 60 seconds.";
                log(Level.SEVERE, logtext);
                for(StackTraceElement s : e.getStackTrace()) {
                    config.logger.log(Level.SEVERE, s.toString());
                }
            }
            return false;
        }
    }

    /**
     * Read the config file
     */
    private void setupConfig() {

        if(this.config == null)
            this.config = new NoCheatConfiguration(new File(NoCheatConfiguration.configFile), new File(NoCheatConfiguration.descriptionsFile));
        else
            this.config.config(new File(NoCheatConfiguration.configFile), new File(NoCheatConfiguration.descriptionsFile));

        config.setupFileLogger();

        try {
            this.chatLevel = config.getLogLevelValue("logging.logtochat");
            this.ircLevel = config.getLogLevelValue("logging.logtoirc");
            this.consoleLevel = config.getLogLevelValue("logging.logtoconsole");
            this.ircTag = config.getStringValue("logging.logtoirctag");
            this.useNewPermissionSystem = this.config.getBooleanValue("newpermsystem");
            this.showStartupMessages = this.config.getBooleanValue("showinfomessages");
        } catch(ConfigurationException e) {
            e.printStackTrace();
            this.setEnabled(false);
        }
    }

    private String getActiveChecksAsString() {

        String s = "";

        for(Check c : checks) {
            s = s + (c.isActive() ? c.getName() + " " : "");
        }

        s = s + (movingCheck.isActive() && !movingCheck.allowFlying ? "flying " : "");
        s = s + (movingCheck.isActive() && !movingCheck.allowFakeSneak ? "fakesneak " : "");
        s = s + (movingCheck.isActive() && !movingCheck.allowFastSwim ? "fastswim " : "");

        return s;
    }

    private String getPermissionsForPlayerAsString(Player p) {

        String s = "";

        for(Check c : checks) {
            s = s + (!c.isActive() ? c.getName() + "* " : (c.skipCheck(p) ? c.getName() + " " : ""));
        }

        s = s + (!movingCheck.isActive() || movingCheck.allowFlying ? "flying* " : (hasPermission(p, PermissionData.PERMISSION_FLYING, movingCheck.checkOPs) ? "flying " : ""));
        s = s + (!movingCheck.isActive() || movingCheck.allowFakeSneak ? "fakesneak* " : (hasPermission(p, PermissionData.PERMISSION_FAKESNEAK, movingCheck.checkOPs) ? "fakesneak " : ""));
        s = s + (!movingCheck.isActive() || movingCheck.allowFastSwim ? "fastswim* " : (hasPermission(p, PermissionData.PERMISSION_FASTSWIM, movingCheck.checkOPs) ? "fastswim " : ""));

        s = s + (hasPermission(p, PermissionData.PERMISSION_NOTIFY, false) ? "notify " : "");

        return s;
    }

    public int getServerTicks() {

        return serverTicks;
    }

    public long getServerLag() {

        return this.serverLagInMilliSeconds;
    }

    public void handleCustomAction(CustomAction a, Player player) {

        String command = a.command.replace("[player]", player.getName());
        try {
            String[] commandParts = command.split(" ", 2);
            String commandName = commandParts[0];
            PluginCommand com = Bukkit.getServer().getPluginCommand(commandName);

            // If there's a plugin that can handle it
            if(com != null) {
                if(commandParts.length > 1) { // Command + parameters
                    String[] commandArgs = commandParts[1].split(" ");
                    com.execute(sender, commandName, commandArgs);
                } else {
                    String[] commandArgs = new String[0];
                    com.execute(sender, commandName, commandArgs);
                }
            } else {
                // The standard server should do it
                Bukkit.getServer().dispatchCommand(sender, command);
            }
        } catch(Exception e) {
            this.log(Level.WARNING, "NoCheat couldn't execute custom server command: \"" + command + "\"");
        }
    }

}
