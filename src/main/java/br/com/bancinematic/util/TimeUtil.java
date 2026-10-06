package br.com.bancinematic.util;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class TimeUtil {
    private TimeUtil() {}

    public static Long parse(String input) {
        try {
            if (input == null || input.length() < 2) return null;

            long value = Long.parseLong(
                    input.substring(0, input.length() - 1)
            );

            if (value <= 0) return null;

            Long seconds = switch (Character.toLowerCase(
                    input.charAt(input.length() - 1))) {
                case 's' -> value;
                case 'm' -> Math.multiplyExact(value, 60);
                case 'h' -> Math.multiplyExact(value, 3600);
                case 'd' -> Math.multiplyExact(value, 86400);
                case 'w' -> Math.multiplyExact(value, 604800);
                default -> null;
            };
            if (seconds == null) return null;

            // Expirations are stored as epoch milliseconds in the database.
            Instant expiration = Instant.now().plusSeconds(seconds);
            if (expiration.toEpochMilli() <= Instant.now().toEpochMilli()) return null;
            return seconds;
        } catch (Exception exception) {
            return null;
        }
    }

    public static String format(long seconds) {
        return format(seconds, null);
    }

    /** Formats all non-zero duration units using the currently selected language. */
    public static String format(long seconds, Function<String, String> translations) {
        if (seconds <= 0) return durationText(translations, "less-than-one-second");

        List<String> parts = new ArrayList<>(5);
        long weeks = seconds / 604800;
        seconds %= 604800;
        addUnit(parts, weeks, translations, "units.week");

        long days = seconds / 86400;
        seconds %= 86400;
        addUnit(parts, days, translations, "units.day");

        long hours = seconds / 3600;
        seconds %= 3600;
        addUnit(parts, hours, translations, "units.hour");

        long minutes = seconds / 60;
        seconds %= 60;
        addUnit(parts, minutes, translations, "units.minute");
        addUnit(parts, seconds, translations, "units.second");

        return String.join(durationText(translations, "separator"), parts);
    }

    /** Formats a live punishment expiry without rounding the displayed time down. */
    public static String formatRemaining(Instant expires) {
        return formatRemaining(expires, null);
    }

    /** Formats a live punishment expiry using the currently selected language. */
    public static String formatRemaining(
            Instant expires,
            Function<String, String> translations
    ) {
        if (expires == null) return durationText(translations, "permanent");

        Duration remaining = Duration.between(Instant.now(), expires);
        if (remaining.isNegative() || remaining.isZero()) {
            return durationText(translations, "less-than-one-second");
        }

        long seconds = remaining.getSeconds();
        if (remaining.getNano() > 0) seconds++;
        return format(seconds, translations);
    }

    private static void addUnit(
            List<String> parts,
            long value,
            Function<String, String> translations,
            String unit
    ) {
        if (value == 0) return;
        String form = value == 1 ? "singular" : "plural";
        parts.add(value + " " + durationText(translations, unit + "." + form));
    }

    private static String durationText(Function<String, String> translations, String key) {
        String path = "messages.duration." + key;
        String translated = translations == null ? null : translations.apply(path);
        if (translated != null && !translated.isBlank()) return translated;

        return switch (key) {
            case "permanent" -> "permanente";
            case "less-than-one-second" -> "menos de 1 segundo";
            case "separator" -> ", ";
            case "units.week.singular" -> "semana";
            case "units.week.plural" -> "semanas";
            case "units.day.singular" -> "dia";
            case "units.day.plural" -> "dias";
            case "units.hour.singular" -> "hora";
            case "units.hour.plural" -> "horas";
            case "units.minute.singular" -> "minuto";
            case "units.minute.plural" -> "minutos";
            case "units.second.singular" -> "segundo";
            case "units.second.plural" -> "segundos";
            default -> key;
        };
    }
}
