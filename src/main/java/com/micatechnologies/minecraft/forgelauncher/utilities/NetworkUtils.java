package com.micatechnologies.minecraft.forgelauncher.utilities;

import com.micatechnologies.minecraft.forgelauncher.consts.AuthConstants;

import java.net.URL;
import java.net.URLConnection;

/**
 * @author Mica Technologies
 * @editors hawka97
 * @creator hawka97
 * @since 1.0
 * @version 1.0
 */
public class NetworkUtils {
    public static boolean isMojangAuthReachable() {
        try {
            URL url = new URL( AuthConstants.AUTH_SERVER_URL );
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
