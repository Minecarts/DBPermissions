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
    
    protected List<PermissionValue> permissions = new ArrayList<PermissionValue>();
    protected HashMap<Player, PermissionAttachment> attachments = new HashMap<Player, PermissionAttachment>();
    
    
    @Override
    public void onEnable() {
        dbq = (DBQuery) getServer().getPluginManager().getPlugin("DBQuery");
        
        getCommand("perm").setExecutor(new CommandExecutor() {
            public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                if(!sender.hasPermission("permission.admin")) return true; // "hide" command output for non-ops

                if(args[0].equalsIgnoreCase("refresh")) {
                    fetchPermissions(true);
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

        Bukkit.getPluginManager().registerEvents(this, this);
        
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                fetchPermissions(false);
            }
        }, 0, 20 * 60 * 2);
    }
    
    @Override
    public void onDisable() {
        for(PermissionAttachment attachment : attachments.values()) {
            attachment.remove();
        }
        attachments.clear();
    }
    
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        calculatePermissions(event.getPlayer());
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        calculatePermissions(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        calculatePermissions(event.getPlayer());
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
        debug("Calculating permissions from cache for player {0} in world {1}", player.getName(), world.getName());
        
        // get this player's attachment
        PermissionAttachment attachment = attachments.get(player);
        if(attachment == null){
            // create permission attachment for player
            debug("No permission attachment found for player {0}, creating attachment", player.getName());
            attachment = player.addAttachment(this);
            attachments.put(player, attachment);
        }
        else {
            // unset existing permissions on player
            for(String key : attachment.getPermissions().keySet()) {
                debug("Unsetting permission node {0} from attachment {1} for player {0}", key, attachment, player.getName());
                attachment.unsetPermission(key);
            }
        }
        
        // set new permissions on player
        for(PermissionValue perm : permissions) {
            // sorting in the query will take care of wildcard permission priority
            // may need to handle it manually if the wildcard changes or player names get symbols
            if(!PermissionValue.WILDCARD.equals(perm.player) && !player.getName().equalsIgnoreCase(perm.player)) {
                //debug("No match for player {0} on permission {1}", player.getName(), perm);
                continue;
            }
            if(!PermissionValue.WILDCARD.equals(perm.world) && !world.getName().equalsIgnoreCase(perm.world)) {
                //debug("No match for world {0} on permission {1}", world.getName(), perm);
                continue;
            }
            
            attachment.setPermission(perm.permission, perm.value);
            debug("Attached permission {0} to attachment {1} for player {2}", perm, attachment, player.getName());
        }
        
        debug("Calling PermissionsCalculated event for player {0}", player.getName());
        getServer().getPluginManager().callEvent(new PermissionsCalculated(player));
    }
    
    
    private void fetchPermissions(boolean async) {
        log("Fetching permissions from database...");
        
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
                + "     AND `g`.`default` = TRUE "
                
                + " ORDER BY `player`, `world` ") {
                    
            @Override
            public void onFetch(ArrayList<HashMap> rows) {
                log("fetchPermissions got {0} rows", rows.size());
                permissions.clear();
                
                for(HashMap row : rows) {
                    PermissionValue perm = new PermissionValue(
                            (String) row.get("permission"),
                            (String) row.get("player"),
                            (String) row.get("world"),
                            (Integer) row.get("value") != 0);
                    permissions.add(perm);
                    debug("Added permission {0} to cache", perm);
                }
                
                debug("Recalculating permissions for all players");
                calculatePermissions();
            }
            
        }.async(async).fetch(PermissionValue.WILDCARD);
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
