package br.thunderradar.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.WeatherData;

import java.util.Locale;

public final class ThunderRadarClient implements ClientModInitializer {
    private static final String MOD_ID = "thunder_radar";
    private static final int SIMULATIONS = 5000;
    private static final int HORIZON_TICKS = 864000; // 12 horas de jogo

    private static long lastForecastAt = 0L;
    private static Forecast cachedForecast = Forecast.empty();
    private static boolean previousStorm = false;

    @Override
    public void onInitializeClient() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "radar"),
                ThunderRadarClient::render
        );
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return; // Somente singleplayer / mundo integrado.

        WeatherData weather = server.getWeatherData();
        boolean stormNow = weather.isRaining() && weather.isThundering();

        if (stormNow && !previousStorm) {
            mc.player.sendSystemMessage(
        Component.literal("§b[Thunder Radar] §fTEMPESTADE! Condutividade pode invocar raios.")
);
        }
        previousStorm = stormNow;

        long now = System.currentTimeMillis();
        if (now - lastForecastAt > 5000L) {
            cachedForecast = calculateForecast(weather);
            lastForecastAt = now;
        }

        int width = mc.getWindow().getGuiScaledWidth();
        int x = width - 184;
        int y = 8;
        int panelWidth = 176;
        int panelHeight = 68;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xB5101724);
        graphics.outline(x, y, panelWidth, panelHeight, 0xFF53677D);
        graphics.text(mc.font, "THUNDER RADAR", x + 7, y + 6, 0xFF8EDBFF, true);

        String current;
        int currentColor;
        if (stormNow) {
            current = "TEMPESTADE ATIVA";
            currentColor = 0xFFFFD166;
        } else if (weather.isRaining()) {
            current = "Chuva normal";
            currentColor = 0xFFB8D8FF;
        } else {
            current = "Tempo limpo";
            currentColor = 0xFFE0E6ED;
        }
        graphics.text(mc.font, current, x + 7, y + 19, currentColor, true);

        String estimate = cachedForecast.medianTicks >= 0
                ? "Próxima: ~" + formatDuration(cachedForecast.medianTicks)
                : "Próxima: sem estimativa";
        graphics.text(mc.font, estimate, x + 7, y + 33, 0xFFFFFFFF, true);

        String chances = String.format(Locale.ROOT, "Chance 1h %d%% | 2h %d%%",
                cachedForecast.oneHourChance, cachedForecast.twoHourChance);
        graphics.text(mc.font, chances, x + 7, y + 47, 0xFFB8C7D9, true);

        graphics.text(mc.font, "Modelo estatístico vanilla", x + 7, y + 58, 0xFF8293A7, false);
    }

    private static Forecast calculateForecast(WeatherData weather) {
        if (weather.isRaining() && weather.isThundering()) {
            return new Forecast(0, 100, 100);
        }

        int rainTimer = Math.max(1, weather.getRainTime());
        int thunderTimer = Math.max(1, weather.getThunderTime());
        int clearTimer = Math.max(0, weather.getClearWeatherTime());

        int oneHour = 20 * 60 * 60;
        int twoHours = oneHour * 2;

        int withinOneHour = 0;
        int withinTwoHours = 0;
        long[] firstStorms = new long[SIMULATIONS];
        int found = 0;

        for (int i = 0; i < SIMULATIONS; i++) {
            long first = simulateFirstStorm(
                    weather.isRaining(),
                    weather.isThundering(),
                    rainTimer,
                    thunderTimer,
                    clearTimer
            );

            if (first >= 0 && first <= HORIZON_TICKS) {
                firstStorms[found++] = first;
                if (first <= oneHour) withinOneHour++;
                if (first <= twoHours) withinTwoHours++;
            }
        }

        int p1 = (int) Math.round(100.0 * withinOneHour / SIMULATIONS);
        int p2 = (int) Math.round(100.0 * withinTwoHours / SIMULATIONS);

        if (found == 0) return new Forecast(-1, p1, p2);

        java.util.Arrays.sort(firstStorms, 0, found);
        long median = firstStorms[found / 2];
        return new Forecast(median, p1, p2);
    }

    /*
     * Simula apenas os próximos estados possíveis. Os valores sorteados são
     * usados localmente para estimar probabilidades; nada é escrito no mundo.
     * Durações vanilla usuais:
     * - chuva ligada: 12.000–24.000 ticks
     * - chuva desligada: 12.000–180.000 ticks
     * - trovão ligado: 3.600–15.600 ticks
     * - trovão desligado: 12.000–180.000 ticks
     */
    private static long simulateFirstStorm(
            boolean raining,
            boolean thundering,
            int rainTime,
            int thunderTime,
            int clearTime
    ) {
        if (raining && thundering) return 0L;

        boolean rain = raining;
        boolean thunder = thundering;
        int r = rainTime;
        int t = thunderTime;
        int clear = clearTime;
        long elapsed = 0L;

        // Um contador zerado agenda o primeiro período sem trocar o estado.
        if (r <= 0) r = rain ? randomBetween(12000, 24000) : randomBetween(12000, 180000);
        if (t <= 0) t = thunder ? randomBetween(3600, 15600) : randomBetween(12000, 180000);

        while (elapsed <= HORIZON_TICKS) {
            if (clear > 0) {
                elapsed += clear;
                clear = 0;
                rain = false;
                thunder = false;
                r = randomBetween(12000, 180000);
                t = randomBetween(12000, 180000);
                continue;
            }

            int step = Math.max(1, Math.min(r, t));
            elapsed += step;
            r -= step;
            t -= step;

            if (r <= 0) {
                boolean wasRaining = rain;
                rain = !rain;
                r = wasRaining
                        ? randomBetween(12000, 24000)
                        : randomBetween(12000, 180000);
            }

            if (t <= 0) {
                boolean wasThundering = thunder;
                thunder = !thunder;
                t = wasThundering
                        ? randomBetween(3600, 15600)
                        : randomBetween(12000, 180000);
            }

            if (rain && thunder) return elapsed;
        }

        return -1L;
    }

    private static int randomBetween(int minInclusive, int maxInclusive) {
        return minInclusive + java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(maxInclusive - minInclusive + 1);
    }

    private static String formatDuration(long ticks) {
        long totalMinutes = Math.max(0, ticks / 1200);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) return hours + "h " + minutes + "min";
        return minutes + " min";
    }

    private record Forecast(long medianTicks, int oneHourChance, int twoHourChance) {
        static Forecast empty() {
            return new Forecast(-1, 0, 0);
        }
    }
}
