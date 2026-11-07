
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

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class ThunderRadarClient implements ClientModInitializer {

    private static final String MOD_ID = "thunder_radar";
    private static final int SIMULATIONS = 5000;

    // Horizonte máximo: 12 horas de jogo.
    private static final int HORIZON_TICKS = 864000;

    private static final int ONE_HOUR_TICKS = 72000;
    private static final int TWO_HOURS_TICKS = 144000;

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

    private static void render(
            GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker
    ) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null || mc.level == null) return;

        MinecraftServer server = mc.getSingleplayerServer();

        // Funciona apenas em mundo singleplayer.
        if (server == null) return;

        WeatherData weather = server.getWeatherData();

        boolean stormNow =
                weather.isRaining() && weather.isThundering();

        // Aviso quando uma tempestade começa.
        if (stormNow && !previousStorm) {
            mc.player.sendSystemMessage(
                    Component.literal(
                            "§b[Thunder Radar] §fTEMPESTADE! " +
                            "Condutividade pode invocar raios."
                    )
            );
        }

        previousStorm = stormNow;

        // Atualiza a previsão a cada 5 segundos reais.
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

        graphics.fill(
                x, y,
                x + panelWidth, y + panelHeight,
                0xB5101724
        );

        graphics.outline(
                x, y,
                panelWidth, panelHeight,
                0xFF53677D
        );

        graphics.text(
                mc.font,
                "THUNDER RADAR",
                x + 7, y + 6,
                0xFF8EDBFF,
                true
        );

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

        graphics.text(
                mc.font,
                current,
                x + 7, y + 19,
                currentColor,
                true
        );

        String estimate =
                cachedForecast.medianTicks >= 0
                        ? "Próxima: ~" +
                          formatDuration(cachedForecast.medianTicks)
                        : "Próxima: sem estimativa";

        graphics.text(
                mc.font,
                estimate,
                x + 7, y + 33,
                0xFFFFFFFF,
                true
        );

        String chances = String.format(
                Locale.ROOT,
                "Chance 1h %.1f%% | 2h %.1f%%",
                cachedForecast.oneHourChance,
                cachedForecast.twoHourChance
        );

        graphics.text(
                mc.font,
                chances,
                x + 7, y + 47,
                0xFFB8C7D9,
                true
        );

        graphics.text(
                mc.font,
                "Estimativa estatística vanilla",
                x + 7, y + 58,
                0xFF8293A7,
                false
        );
    }

    private static Forecast calculateForecast(WeatherData weather) {

        if (weather.isRaining() && weather.isThundering()) {
            return new Forecast(0, 100.0, 100.0);
        }

        int rainTimer = weather.getRainTime();
        int thunderTimer = weather.getThunderTime();
        int clearTimer = weather.getClearWeatherTime();

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

                if (first <= ONE_HOUR_TICKS) {
                    withinOneHour++;
                }

                if (first <= TWO_HOURS_TICKS) {
                    withinTwoHours++;
                }
            }
        }

        double p1 = 100.0 * withinOneHour / SIMULATIONS;
        double p2 = 100.0 * withinTwoHours / SIMULATIONS;

        // Sem tempestades suficientes para estimar uma mediana confiável.
        if (found < (SIMULATIONS + 1) / 2) {
            return new Forecast(-1, p1, p2);
        }

        Arrays.sort(firstStorms, 0, found);

        long median = firstStorms[found / 2];

        return new Forecast(median, p1, p2);
    }

    /*
     * Simula os ciclos de chuva e trovão sem alterar o mundo.
     *
     * Intervalos vanilla:
     * Chuva ativa:       12.000 a 23.999 ticks
     * Chuva inativa:     12.000 a 179.999 ticks
     * Trovão ativo:       3.600 a 15.599 ticks
     * Trovão inativo:    12.000 a 179.999 ticks
     *
     * Os temporizadores são independentes.
     */
    private static long simulateFirstStorm(
            boolean raining,
            boolean thundering,
            int rainTime,
            int thunderTime,
            int clearTime
    ) {

        if (raining && thundering) {
            return 0L;
        }

        /*
         * Tempo limpo forçado:
         * enquanto ele existir, chuva e trovão ficam desligados.
         * Quando termina, os dois ciclos podem começar novamente.
         */
        if (clearTime > 0) {
            return Math.min(
                    HORIZON_TICKS + 1L,
                    (long) clearTime + 1L
            );
        }

        boolean rain = raining;
        boolean thunder = thundering;

        int r = rainTime;
        int t = thunderTime;

        long elapsed = 0L;

        // Se o temporizador já expirou, inicia uma nova duração.
        if (r <= 0) {
            r = nextRainDuration(rain);
        }

        if (t <= 0) {
            t = nextThunderDuration(thunder);
        }

        while (elapsed <= HORIZON_TICKS) {

            int step = Math.max(1, Math.min(r, t));

            elapsed += step;

            r -= step;
            t -= step;

            // O estado da chuva muda quando o contador termina.
            if (r <= 0) {
                rain = !rain;

                // A duração sorteada corresponde ao NOVO estado.
                r = nextRainDuration(rain);
            }

            // O estado do trovão muda quando o contador termina.
            if (t <= 0) {
                thunder = !thunder;

                // A duração sorteada corresponde ao NOVO estado.
                t = nextThunderDuration(thunder);
            }

            if (rain && thunder) {
                return elapsed;
            }
        }

        return -1L;
    }

    private static int nextRainDuration(boolean raining) {

        if (raining) {
            return randomBetween(12000, 23999);
        }

        return randomBetween(12000, 179999);
    }

    private static int nextThunderDuration(boolean thundering) {

        if (thundering) {
            return randomBetween(3600, 15599);
        }

        return randomBetween(12000, 179999);
    }

    private static int randomBetween(int min, int max) {

        return ThreadLocalRandom.current()
                .nextInt(min, max + 1);
    }

    private static String formatDuration(long ticks) {

        long totalMinutes = Math.max(0, ticks / 1200);

        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "min";
        }

        return minutes + " min";
    }

    private record Forecast(
            long medianTicks,
            double oneHourChance,
            double twoHourChance
    ) {

        static Forecast empty() {
            return new Forecast(-1, 0.0, 0.0);
        }
    }
}
