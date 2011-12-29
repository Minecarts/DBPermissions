package com.minecarts.dbpermissions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.text.MessageFormat;

import com.minecarts.dbquery.DBQuery;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.Event.Type;
import org.bukkit.event.Event.Priority;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerQuitEvent;


import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachment;


public class DBPermissions extends org.bukkit.plugin.java.JavaPlugin {
    private static final Logger logger = Logger.getLogger("com.minecarts.cowardkiller");

    private DBQuery dbq;

    protected boolean debug;
    protected HashMap<Player,PermissionAttachment> attachments = new HashMap<Player, PermissionAttachment>();


    public void onEnable() {
        dbq = (DBQuery) getServer().getPluginManager().getPlugin("DBQuery");

        // reload config command
        getCommand("perm").setExecutor(new CommandExecutor() {
            public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                if(!sender.hasPermission("permission.admin")) return true; // "hide" command output for non-ops

                if(args[0].equalsIgnoreCase("reload")) {
                    DBPermissions.this.reloadConfig();
                    sender.sendMessage("DBPermissions config reloaded.");
                    return true;
                }

                if(args[0].equalsIgnoreCase("check")) {
                    if(args.length != 3) return false;
                    Player p = Bukkit.getPlayer(args[1]);
                    String permission = args[2];
                    sender.sendMessage(p.getName() + " has " + permission + " set to " + p.hasPermission(permission));
                    return true;
                }

                return false;
            }
        });
        
        //Create the player listener
        PlayerListener listener = new PlayerListener(){
            @Override
            public void onPlayerJoin(PlayerJoinEvent event){
                registerPlayer(event.getPlayer());
                calculatePermissions(event.getPlayer());
            }

            @Override
            public void onPlayerQuit(PlayerQuitEvent event) {
                unregisterPlayer(event.getPlayer());
            }

            @Override
            public void onPlayerTeleport(PlayerTeleportEvent event){
                if(event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
                calculatePermissions(event.getPlayer(),event.getTo().getWorld());
            }
        };


        getServer().getPluginManager().registerEvent(Type.PLAYER_JOIN, listener, Priority.Lowest, this);
        getServer().getPluginManager().registerEvent(Type.PLAYER_QUIT, listener, Priority.Monitor, this);
        getServer().getPluginManager().registerEvent(Type.PLAYER_TELEPORT, listener, Priority.Monitor, this);

        //Save the default config
        getConfig().options().copyDefaults(true);
        this.saveConfig();

        log("Version {0} enabled.", getDescription().getVersion());
    }


//Permission functionality
    public void registerPlayer(Player player){
        if(attachments.containsKey(player)){
            debug("Warning while registering:" + player.getName() + " already had an attachment");
            unregisterPlayer(player);
        }
        PermissionAttachment attachment = player.addAttachment(this);
        attachments.put(player,attachment);
        debug("Added attachment for " + player.getName());
    }
    
    public void unregisterPlayer(Player player){
        if(attachments.containsKey(player)) {
            try { player.removeAttachment(attachments.get(player)); }
            catch (IllegalArgumentException ex) { debug("Unregistering for " + player.getName() + " failed: No attachment"); }
            this.attachments.remove(player);
            debug("Attachment unregistered for " + player.getName());
        } else {
            debug("Unregistering for " + player + " failed: No stored attachment");
        }
    }

    public void calculatePermissions(final Player player){
        calculatePermissions(player,player.getWorld());
    }
    public void calculatePermissions(final Player player, final World world){
        //Get this players attachment
        final PermissionAttachment attachment = attachments.get(player);
        
        //Unset all the permissions for this player as we're recalculating them
        //  We're doing this outside the query to make sure that if for some reason the DB
        //  goes down this player doesn't have permissions they shouldn't have
        for(String key : attachment.getPermissions().keySet()){
            attachment.unsetPermission(key);
        }

        //Find the group permissions (and any default groups), and assign those permissions
        new Query("SELECT * FROM `permissions`, `player_groups`, `groups` WHERE (`permissions`.`world` = ? OR `permissions`.`world` = '*') AND  (`groups`.`default` = 1  AND `groups`.`group` = `permissions`.`identifier`) OR ( (`permissions`.`identifier` = `player_groups`.`group`) AND `groups`.`default` = 0 AND (`player_groups`.`player` = ? ) AND `permissions`.`type` = 'group')"){
            @Override
            public void onFetch(ArrayList<HashMap> rows){
                for(HashMap row : rows){
                    attachment.setPermission((String)row.get("permission"),(Boolean)row.get("value"));
                    debug("Set GROUP ("+ row.get("identifier") + ") " + row.get("permission") + " for " + player.getName() + " to " + row.get("value"));
                }

                //Now find the player permissions, and assign those, so they always override the group ones
                new Query("SELECT `permission`, `value` FROM `permissions` WHERE `permissions`.`identifier` = ? AND `permissions`.`type` = 'player' AND (`permissions`.`world` = ? OR `permissions`.`world` = '*')"){
                    @Override
                    public void onFetch(ArrayList<HashMap> rows){
                        for(HashMap row : rows){
                            attachment.setPermission((String)row.get("permission"),(Boolean)row.get("value"));
                            debug("Set PLAYER " + row.get("permission") + " for " + player.getName() + " to " + row.get("value"));
                        }
                    }
                }.fetch(player.getName(),
                        world.getName());
            }
        }.fetch(world.getName(),
                player.getName());
    }
    
//Database functionality
    class Query extends com.minecarts.dbquery.Query {
        public Query(String sql) {
            super(DBPermissions.this, dbq.getProvider(getConfig().getString("db.provider")), sql);
        }
        @Override
        public void onComplete(FinalQuery query) {
            if(query.elapsed() > 500) {
                log(MessageFormat.format("Slow query took {0,number,#} ms", query.elapsed()));
            }
        }
        @Override
        public void onException(Exception x, FinalQuery query) {
            try { throw x; }
            catch(Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    
//Internal functionality
    public void onDisable() {
    }

    public void log(String message) {
        log(Level.INFO, message);
    }
    public void log(Level level, String message) {
        logger.log(level, MessageFormat.format("{0}> {1}", getDescription().getName(), message));
    }
    public void log(String message, Object... args) {
        log(MessageFormat.format(message, args));
    }
    public void log(Level level, String message, Object... args) {
        log(level, MessageFormat.format(message, args));
    }

    public void debug(String message) {
        if(getConfig().getBoolean("debug")) log(message);
    }
    public void debug(String message, Object... args) {
        if(getConfig().getBoolean("debug")) log(message, args);
    }
}
