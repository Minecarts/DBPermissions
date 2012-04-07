package com.minecarts.dbpermissions;

import java.util.*;
import java.util.logging.Level;
import java.text.MessageFormat;

import com.minecarts.dbquery.DBQuery;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.*;


import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;


public class DBPermissions extends org.bukkit.plugin.java.JavaPlugin implements Listener {
    private DBQuery dbq;
    
    protected Set<Permission> permissions = new HashSet<Permission>();
    protected HashMap<Player, PermissionAttachment> attachments = new HashMap<Player, PermissionAttachment>();
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if(event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        registerPlayer(event.getPlayer());
        calculatePermissions(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        unregisterPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        unregisterPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        calculatePermissions(event.getPlayer(), event.getPlayer().getWorld());
    }
    
    
    @Override
    public void onEnable() {
        dbq = (DBQuery) getServer().getPluginManager().getPlugin("DBQuery");
        
        getCommand("perm").setExecutor(new CommandExecutor() {
            public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                if(!sender.hasPermission("permission.admin")) return true; // "hide" command output for non-ops

                if(args[0].equalsIgnoreCase("refresh")) {
                    fetchPermissions();
                    sender.sendMessage(ChatColor.GRAY + "Fetching and recalculating permissions...");
                    return true;
                }
                else if(args[0].equalsIgnoreCase("reload")) {
                    DBPermissions.this.reloadConfig();
                    sender.sendMessage("DBPermissions config reloaded.");
                    return true;
                }
                else if(args[0].equalsIgnoreCase("check")) {
                    if(args.length != 3) return false;
                    Player p = Bukkit.getPlayer(args[1]);
                    String permission = args[2];
                    sender.sendMessage(p.getName() + " has " + permission + " set to " + p.hasPermission(permission));
                    return true;
                }

                return false;
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        
        for(Player player : Bukkit.getOnlinePlayers()) {
            registerPlayer(player);
        }
        fetchPermissions();
    }
    
    @Override
    public void onDisable() {
        for(Player player : Bukkit.getOnlinePlayers()) {
            unregisterPlayer(player);
        }
    }


    
    public void registerPlayer(Player player) {
        if(attachments.containsKey(player)) {
            debug("Warning while registering: " + player.getName() + " already had an attachment");
            unregisterPlayer(player);
        }
        PermissionAttachment attachment = player.addAttachment(this);
        attachments.put(player, attachment);
        debug("Added attachment for " + player.getName());
    }
    
    public void unregisterPlayer(Player player) {
        if(attachments.containsKey(player)) {
            try {
                player.removeAttachment(attachments.get(player));
            }
            catch (IllegalArgumentException ex) {
                debug("Unregistering for " + player.getName() + " failed: No attachment");
            }
            this.attachments.remove(player);
            debug("Attachment unregistered for " + player.getName());
        } else {
            debug("Unregistering for " + player + " failed: No stored attachment");
        }
    }
    
    
    public void calculatePermissions() {
        for(Player player : Bukkit.getOnlinePlayers()) {
            calculatePermissions(player);
        }
    }
    public void calculatePermissions(Player player) {
        calculatePermissions(player, player.getWorld());
    }
    public void calculatePermissions(Player player, World world) {
        debug("Calculating player permissions from cache for {0}", player.getName());
        
        // get this player's attachment
        PermissionAttachment attachment = attachments.get(player);
        if(attachment == null){
            log(Level.SEVERE, "ERROR: " + player.getName() + " does not have an attachment when calculating permissions. Aborting permission load.");
            return;
        }
        
        // unset existing permissions on player
        for(String key : attachment.getPermissions().keySet()) {
            attachment.unsetPermission(key);
        }
        
        // set new permissions on player
        for(Permission perm : permissions) {
            // sorting in the query will take care of wildcard permission priority
            // may need to handle it manually if the wildcard changes or player names get symbols
            if(!Permission.WILDCARD.equals(perm.player) && !player.getName().equalsIgnoreCase(perm.player)) continue;
            if(!Permission.WILDCARD.equals(perm.world) && !world.getName().equalsIgnoreCase(perm.world)) continue;
            
            attachment.setPermission(perm.permission, perm.value);
        }
        
        getServer().getPluginManager().callEvent(new PermissionsCalculated(player));
    }
    
    
    private void fetchPermissions() {
        new Query(" SELECT `permission`, `identifier` AS `player`, `world`, `value` "
                + " FROM `permissions` "
                + " WHERE `type` = 'player' "
                
                + " UNION "
                
                + " SELECT `p`.`permission`, `pg`.`player`, `p`.`world`, `p`.`value` "
                + " FROM `permissions` `p` "
                + "     JOIN `player_groups` `pg` ON `pg`.`group` = `p`.`identifier` "
                + " WHERE `p`.`type` = 'group' "
                
                + " UNION "
                
                + " SELECT `p`.`permission`, ?, `p`.`world`, `p`.`value` "
                + " FROM `permissions` `p` "
                + "     JOIN `groups` `g` ON `g`.`group` = `p`.`identifier` "
                + " WHERE `p`.`type` = 'group' "
                + " AND `g`.`default` = TRUE "
                
                + " ORDER BY `player`, `world` ") {
                    
            @Override
            public void onFetch(ArrayList<HashMap> rows) {
                permissions.clear();
                
                for(HashMap row : rows) {
                    permissions.add(new Permission(
                            (String) row.get("permission"),
                            (String) row.get("player"),
                            (String) row.get("world"),
                            (Integer) row.get("value") != 0));
                }
                
                calculatePermissions();
            }
            
        }.fetch(Permission.WILDCARD);
    }
    
    
    class Query extends com.minecarts.dbquery.Query {
        public Query(String sql) {
            super(DBPermissions.this, dbq.getProvider(getConfig().getString("db.provider")), sql);
        }
        @Override
        public void onBeforeCallback(FinalQuery query) {
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
    
    
    
    public void log(String message) {
        log(Level.INFO, message);
    }
    public void log(Level level, String message) {
        getLogger().log(level, message);
    }
    public void log(String message, Object... args) {
        log(MessageFormat.format(message, args));
    }
    public void log(Level level, String message, Object... args) {
        log(level, MessageFormat.format(message, args));
    }

    public void debug(String message) {
        log(Level.FINE, message);
    }
    public void debug(String message, Object... args) {
        debug(MessageFormat.format(message, args));
    }
}
