package com.minecarts.dbpermissions;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import org.bukkit.permissions.Permissible;

public class PermissionsCalculated extends Event {
    private static final HandlerList handlers = new HandlerList();
    
    private Permissible permissible;
    
    public PermissionsCalculated(Permissible permissible) {
        this.permissible = permissible;
    }
    
    public Permissible getPermissible() {
        return this.permissible;
    }
    
    
    public HandlerList getHandlers() {
        return handlers;
    }
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
