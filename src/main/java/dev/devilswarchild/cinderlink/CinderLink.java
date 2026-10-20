package dev.devilswarchild.cinderlink;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

/**
 * CinderLink entry point. dist = CLIENT means this class is never constructed on a dedicated server.
 */
@Mod(value = CinderLink.MODID, dist = Dist.CLIENT)
public final class CinderLink {
    public static final String MODID = "cinderlink";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CinderLink(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CinderLinkConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        LOGGER.info("CinderLink loaded (client-only).");
    }
}
