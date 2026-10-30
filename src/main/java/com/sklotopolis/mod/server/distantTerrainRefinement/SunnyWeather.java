package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.weather.Weather;

import java.util.Properties;
import java.util.logging.Logger;

/**
 * Tilts vanilla's weather random walk towards fair weather. Runs after every Weather.tick() (one per
 * ~70 s) and only nudges the two targets the walk drifts towards, through Weather's public setters:
 * vanilla's own smoothing, seasonal factors and clamps still shape the result, so weather changes
 * as gradually as before - it just spends more time clear.
 *
 * Measured with a 2M-tick replay of vanilla's tick() (all seasons and weekdays):
 *   vanilla               rain 30% of the time, heavy rain (>0.5) 5.4%, sunny 26%, overcast 22%
 *   0.15 rain / 0.007 sky rain 15%,                heavy rain 1.0%,     sunny 48%, overcast  8%
 * (sunny = cloudiness < 0.25 and dry, overcast = cloudiness > 0.6). The cloud bias is by far the
 * stronger knob, because vanilla caps rainTarget at cloudiness: 0.02 already drove heavy rain and
 * overcast to ~0%, i.e. no real storms ever, which is why the default stays well below that.
 */
public final class SunnyWeather {
    private static final Logger logger = Logger.getLogger(SunnyWeather.class.getName());

    // Vanilla's own lower clamp on rainTarget (weekdays other than Monday).
    private static final float RAIN_TARGET_MIN = -16.0f;

    private static volatile float rainBias = 0.15f;
    private static volatile float cloudBias = 0.007f;

    private SunnyWeather() {
    }

    static void configure(Properties properties) {
        rainBias = read(properties, "weatherRainBias", rainBias);
        cloudBias = read(properties, "weatherCloudBias", cloudBias);
        logger.info("SunnyWeather: rain bias " + rainBias + ", cloud bias " + cloudBias
                + (rainBias == 0 && cloudBias == 0 ? " (off, vanilla weather)" : ""));
    }

    private static float read(Properties properties, String name, float fallback) {
        try {
            return Math.max(0.0f, Float.parseFloat(properties.getProperty(name, Float.toString(fallback)).trim()));
        } catch (NumberFormatException e) {
            logger.warning("SunnyWeather: " + name + " is not a number, using " + fallback);
            return fallback;
        }
    }

    public static void afterTick(Weather weather) {
        if (rainBias > 0) {
            weather.setRainTarget(Math.max(RAIN_TARGET_MIN, weather.getRainTarget() - rainBias));
        }
        if (cloudBias > 0) {
            // No lower clamp needed: the next tick() clamps cloudTarget to its weekday range first.
            weather.setCloudTarget(weather.getCloudTarget() - cloudBias);
        }
    }
}
