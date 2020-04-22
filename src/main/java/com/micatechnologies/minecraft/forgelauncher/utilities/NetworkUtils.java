package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.micatechnologies.minecraft.forgelauncher.auth.MCAuthConstants;

import java.net.URL;
import java.net.URLConnection;

public class NetworkUtils {
    public static boolean isMojangAuthReachable() {
        try {
            URL url = new URL( MCAuthConstants.MC_AUTH_SERVER_URL );
            URLConnection connection = url.openConnection();
            if ( connection.getContentLength() == -1 ) {
                return false;
            }
        }
        catch ( Exception e ) {
            return false;
        }
        return true;
    }
}
