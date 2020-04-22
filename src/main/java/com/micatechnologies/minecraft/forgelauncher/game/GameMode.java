package com.micatechnologies.minecraft.forgelauncher.game;

public enum GameMode {
    CLIENT( "CLIENT" ), SERVER( "SERVER" );

    private final String gameModeString;

    GameMode( String gameModeString ) {
        this.gameModeString = gameModeString;
    }

    public String getStringName() {
        return gameModeString;
    }
}
