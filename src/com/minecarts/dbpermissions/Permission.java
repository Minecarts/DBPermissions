package com.minecarts.dbpermissions;

public class Permission {
    public static final String WILDCARD = "*";
    
    public final String permission;
    public final String player;
    public final String world;
    public final boolean value;
    
    public Permission(String permission, String player, String world, boolean value) {
        this.permission = permission;
        this.player = player;
        this.world = world;
        this.value = value;
    }
}
