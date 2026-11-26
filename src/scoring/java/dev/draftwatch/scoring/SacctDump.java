package dev.draftwatch.scoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elapsed times from a one-time {@code sacct -P -X -o JobName,Elapsed,State} dump captured on
 * the cluster. Only COMPLETED jobs are kept.
 */
public final class SacctDump {
  static final String HEADER = "JobName|Elapsed|State";
  private static final Pattern ELAPSED = Pattern.compile("(?:(\\d+)-)?(\\d+):(\\d{2}):(\\d{2})");

  private SacctDump() {}

  /**
   * Job name to elapsed time.
   *
   * @throws DataException naming the file and line of a wrong header, a malformed line, or a
   *     job name that appears twice
   */
  public static Map<String, Duration> parse(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new DataException(file + ": cannot read: " + e.getMessage(), e);
    }
    if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
      throw new DataException(file + " line 1: expected the header " + HEADER);
    }
    Map<String, Duration> out = new TreeMap<>();
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isEmpty()) {
        continue;
      }
      String where = file + " line " + (i + 1);
      String[] f = line.split("\\|", -1);
      if (f.length != 3 || f[0].isEmpty()) {
        throw new DataException(where + ": expected JobName|Elapsed|State, got " + line);
      }
      Matcher m = ELAPSED.matcher(f[1]);
      if (!m.matches()) {
        throw new DataException(where + ": elapsed time " + f[1] + " is not [D-]HH:MM:SS");
      }
      if (!f[2].equals("COMPLETED")) {
        continue;
      }
      long days = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
      Duration d =
          Duration.ofDays(days)
              .plusHours(Long.parseLong(m.group(2)))
              .plusMinutes(Long.parseLong(m.group(3)))
              .plusSeconds(Long.parseLong(m.group(4)));
      if (out.put(f[0], d) != null) {
        throw new DataException(where + ": job " + f[0] + " appears twice");
      }
    }
    return out;
  }
}
