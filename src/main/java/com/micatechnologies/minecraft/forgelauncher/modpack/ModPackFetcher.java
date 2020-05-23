package com.micatechnologies.minecraft.forgelauncher.modpack;

import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Class for fetching mod pack objects from their manifest URL.
 *
 * @author Mica Technologies/hawka97
 * @version 1.0
 */
public class ModPackFetcher {

    /**
     * Fetches the mod pack object from the specified manifest URL.
     *
     * @param manifestUrl mod pack manifest URL
     * @return mod pack object
     */
    public static ModPack get(String manifestUrl) throws IOException {
        // Fetch contents of available mod pack manifest
        String manifestBody = IOUtils.toString(new URL(manifestUrl), Charset.defaultCharset());

        // Parse available mod pack manifest contents
        ModPack modPack = new Gson().fromJson(manifestBody, ModPack.class);
        modPack.manifestUrl = manifestUrl;
        return modPack;
    }
}
