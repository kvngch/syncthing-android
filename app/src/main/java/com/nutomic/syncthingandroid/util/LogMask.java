package com.nutomic.syncthingandroid.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks what identifies peers in a log before it is shared: full device IDs keep their first
 * group only, IPv4 addresses with a port keep their first two bytes, IPv6 addresses with a port
 * are hidden. Loopback and unspecified addresses are kept, they say nothing about the peers.
 */
public final class LogMask {

    private static final Pattern DEVICE_ID =
            Pattern.compile("\\b([A-Z2-7]{7})(?:-[A-Z2-7]{7}){7}\\b");
    private static final Pattern IPV4_WITH_PORT =
            Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})\\b");
    private static final Pattern IPV6_WITH_PORT =
            Pattern.compile("\\[([0-9a-fA-F:.%]*:[0-9a-fA-F:.%]*)\\](:\\d{1,5})");

    private LogMask() {
    }

    public static String maskLine(String line) {
        line = DEVICE_ID.matcher(line).replaceAll("$1-XXXXXXX-...");

        Matcher ipv4 = IPV4_WITH_PORT.matcher(line);
        StringBuffer masked = new StringBuffer();
        while (ipv4.find()) {
            String address = ipv4.group();
            boolean keep = address.startsWith("127.") || address.startsWith("0.0.0.0:");
            ipv4.appendReplacement(masked, keep
                    ? Matcher.quoteReplacement(address)
                    : Matcher.quoteReplacement(ipv4.group(1) + ".x.x" + ipv4.group(2)));
        }
        ipv4.appendTail(masked);
        line = masked.toString();

        Matcher ipv6 = IPV6_WITH_PORT.matcher(line);
        masked = new StringBuffer();
        while (ipv6.find()) {
            String host = ipv6.group(1);
            boolean keep = host.equals("::1") || host.equals("::");
            ipv6.appendReplacement(masked, keep
                    ? Matcher.quoteReplacement(ipv6.group())
                    : Matcher.quoteReplacement("[x:x]" + ipv6.group(2)));
        }
        ipv6.appendTail(masked);
        return masked.toString();
    }

    /**
     * Writes source to target line by line through {@link #maskLine}.
     */
    public static void maskFile(File source, File target) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(maskLine(line));
                writer.write('\n');
            }
        }
    }
}
